package com.kynetics.backpressure.decoder

import scala.util.Try
import upickle.default.{Reader, Writer, read, write}

object JsonSupport {
  def decode[A: Reader](body: String): Either[String, A] =
    Try(read[A](body)).toEither.left.map(_.getMessage)

  def encode[A: Writer](value: A): String =
    write(value)
}
