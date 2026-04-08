package com.kynetics.backpressure.generator

import java.awt.image.BufferedImage
import java.awt.{Color, Font, RenderingHints}
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

object ImageRenderer:
  System.setProperty("java.awt.headless", "true")

  def renderPngBase64(number: Int, width: Int, height: Int): String =
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()

    try
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      graphics.setColor(Color.WHITE)
      graphics.fillRect(0, 0, width, height)
      graphics.setColor(new Color(24, 24, 24))
      val fontSize = math.max(12, math.min(height - 4, width / math.max(number.toString.length, 1)))
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize))

      val label = number.toString
      val metrics = graphics.getFontMetrics
      val x = math.max(2, (width - metrics.stringWidth(label)) / 2)
      val y = math.max(metrics.getAscent, ((height - metrics.getHeight) / 2) + metrics.getAscent)
      graphics.drawString(label, x, y)
    finally graphics.dispose()

    val output = new ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    Base64.getEncoder.encodeToString(output.toByteArray)
