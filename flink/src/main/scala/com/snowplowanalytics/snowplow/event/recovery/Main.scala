/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and
 * limitations there under.
 */
package com.snowplowanalytics.snowplow.event.recovery

import scala.concurrent.duration.{MILLISECONDS, NANOSECONDS, TimeUnit}
import cats.Id
import cats.effect._
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import util.paths._
import util.base64
import config._

object Main
    extends CommandIOApp(
      name   = "snowplow-event-recovery-job",
      header = "Snowplow event recovery job"
    ) {
  override def main: Opts[IO[ExitCode]] = {
    val input = Opts.option[String](
      "input",
      help = "Input S3 path"
    )
    val failedOutput = Opts
      .option[String](
        "failedOutput",
        help = "Unrecovered (bad row) output S3 path. Defaults to `input`"
      )
      .orElse(input.map(failedPath))
    val unrecoverableOutput = Opts
      .option[String](
        "unrecoverableOutput",
        help = "Unrecoverable (bad row) output S3 path. Defaults failedOutput/unrecoverable` or `input/unrecoverable`"
      )
      .orElse(failedOutput.map(unrecoverablePath))
      .orElse(input.map(unrecoverablePath))
    val output = Opts.option[String](
      "output",
      help = "Output Kinesis topic"
    )
    val resolver = Opts
      .option[String](
        "resolver",
        help = "Iglu resolver configuration"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)
    val config = Opts
      .option[String](
        "config",
        help = "Base64 config with schema com.snowplowanalytics.snowplow/recovery_config/jsonschema/1-0-0"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)

    val validatedConfig = (resolver, config).mapN((r, c) => validateSchema[Id](c, r).map(_ => c).value.flatMap(load(_)))

    (input, output, failedOutput, unrecoverableOutput, validatedConfig).mapN { (i, o, f, u, c) =>
      IO.fromEither(
        c.map(RecoveryJob.run(i, o, f, u, _)).map(_ => ExitCode.Success).leftMap(new RuntimeException(_))
      )
    }
  }

  implicit val catsClockIdInstance: Clock[Id] = new Clock[Id] {
    override def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), NANOSECONDS)
    override def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), MILLISECONDS)
  }
}
