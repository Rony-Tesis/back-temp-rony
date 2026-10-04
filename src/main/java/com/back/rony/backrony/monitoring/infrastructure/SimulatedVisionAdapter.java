package com.back.rony.backrony.monitoring.infrastructure;

import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Java-only demo source. It cannot create or access a physical robot adapter. */
public final class SimulatedVisionAdapter implements VisionPort {
    private final int fps;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

    public SimulatedVisionAdapter(int fps) {
        this.fps = fps;
    }

    @Override
    public void start(Consumer<Observation> observed, Consumer<String> failed) {
        clock.scheduleWithFixedDelay(
                () -> {
                    try {
                        BufferedImage image =
                                new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics();
                        try {
                            g.setColor(new Color(24, 35, 24));
                            g.fillRect(0, 0, 640, 480);
                            g.setColor(new Color(40, 56, 40));
                            for (int x = 0; x < 640; x += 40) g.drawLine(x, 0, x, 480);
                            for (int y = 0; y < 480; y += 40) g.drawLine(0, y, 640, y);
                            g.setColor(new Color(60, 190, 90));
                            g.setStroke(new BasicStroke(3));
                            g.drawRect(265, 180, 110, 120);
                            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                            g.drawString("RONY - CAMARA SIMULADA", 25, 40);
                            g.setColor(Color.WHITE);
                            g.drawString("NO HAY MOVIMIENTO FISICO", 25, 445);
                        } finally {
                            g.dispose();
                        }
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        ImageIO.write(image, "jpg", out);
                        Detection detection = Detection.demo(0);
                        observed.accept(
                                new Observation(out.toByteArray(), detection, 50, detection));
                    } catch (IOException e) {
                        failed.accept("Cámara simulada no disponible.");
                    }
                },
                0,
                1000 / fps,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
