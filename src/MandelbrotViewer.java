import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MandelbrotViewer extends JFrame {
    private int width = 800;
    private int height = 600;
    private static final int MAX_ITER = 100;
    private static final int TARGET_FPS = 60;

    private double xMin = -2.0, xMax = 1.0;
    private double yMin = -1.2, yMax = 1.2;
    private double zoomFactor = 0.8;
    private double panSpeed;
    private BufferedImage image;
    private boolean rendering = false;
    //type in command line when launching --nongui or --test to run either of them for testing
    //if you want the set to just render normally with gui, then leave it as blank
    private static boolean headlessMode = false; //nongui bool
    private static boolean runTests = false; // test bool

    // for parallel
    private BlockingQueue<Chunk> chunkQueue;

    public MandelbrotViewer() {
        if (!headlessMode) {
            setTitle("Mandelbrot-Parallel Window");
            setSize(width, height);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLocationRelativeTo(null);
            setResizable(true);


            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT: // Movements
                            xMin -= panSpeed;
                            xMax -= panSpeed;
                            break;
                        case KeyEvent.VK_RIGHT:
                            xMin += panSpeed;
                            xMax += panSpeed;
                            break;
                        case KeyEvent.VK_UP:
                            yMin -= panSpeed;
                            yMax -= panSpeed;
                            break;
                        case KeyEvent.VK_DOWN:
                            yMin += panSpeed;
                            yMax += panSpeed;
                            break;
                        case KeyEvent.VK_1: // Zoom in
                            zoom(zoomFactor);
                            break;
                        case KeyEvent.VK_2: // Zoom out
                            zoom(1 / zoomFactor);
                            break;
                        case KeyEvent.VK_S: // Save
                            promptImageSize();
                            saveImage();
                            break;
                    }
                    startRendering();
                }
            });
        }

        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        panSpeed = 0.1 * (xMax - xMin);


        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                width = getWidth();
                height = getHeight();
                startRendering();
            }
        });

        startRendering();
    }

    private void promptImageSize() {
        Scanner scanner = new Scanner(System.in);
        Logger.log("Enter image width: ", LogLevel.Warn);
        width = scanner.nextInt();
        Logger.log("Enter image height: ", LogLevel.Warn);
        height = scanner.nextInt();
        setSize(width, height);
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        startRendering();
    }

    private void zoom(double factor) {
        double xCenter = (xMin + xMax) / 2;
        double yCenter = (yMin + yMax) / 2;
        double xRange = (xMax - xMin) * factor;
        double yRange = (yMax - yMin) * factor;
        xMin = xCenter - xRange / 2;
        xMax = xCenter + xRange / 2;
        yMin = yCenter - yRange / 2;
        yMax = yCenter + yRange / 2;
        panSpeed = 0.1 * (xMax - xMin); // Adjust the pan speed for diff zoom levels
    }

    private void startRendering() {
        if (rendering) return;

        rendering = true;
        new Thread(() -> {
            renderMandelbrot();
            rendering = false;
            if (!headlessMode) repaint();
        }).start();
    }

    private void renderMandelbrot() {
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int numThreads = Runtime.getRuntime().availableProcessors();
        final int chunkSize = 30; // chunk = 30x30 px
        int numChunksX = (int) Math.ceil((double) width / chunkSize);
        int numChunksY = (int) Math.ceil((double) height / chunkSize);
        chunkQueue = new ArrayBlockingQueue<>(numChunksX * numChunksY, true);
        // Napolnimo čakalno vrsto s chunki
        for (int chunkX = 0; chunkX < numChunksX; chunkX++) {
            for (int chunkY = 0; chunkY < numChunksY; chunkY++) {
                int startX = chunkX * chunkSize;
                int endX = Math.min(startX + chunkSize, width);
                int startY = chunkY * chunkSize;
                int endY = Math.min(startY + chunkSize, height);
                chunkQueue.offer(new Chunk(startX, startY, endX, endY));
            }
        }
        // Zaženemo niti
        List<Thread> threads = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < numThreads; i++) {
            Thread thread = new Thread(() -> {
                Chunk chunk;
                while ((chunk = chunkQueue.poll()) != null) {
                    processChunk(chunk, width, height); // obdelaj chunk
                }
            });
            threads.add(thread);
            thread.start();
        }
        // Počakamo na vse niti
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long end = System.currentTimeMillis();
        if (!headlessMode) {
            Logger.log("Render time: " + (end - start) + " ms", LogLevel.Info);
        }
        repaint();
    }

    private int computePoint(Complex c) {
        Complex z = new Complex(0, 0);
        int n = 0;

        while (z.abs() <= 2 && n < MAX_ITER) {
            z = z.multiply(z).add(c);
            n++;
        }

        if (n == MAX_ITER) {
            return Color.BLACK.getRGB(); // black for inside of the set
        }

        // color gradient
        float hue = 0.7f + (float) n / MAX_ITER;
        float saturation = 1.0f;
        float brightness = n < MAX_ITER ? 1.0f : 0.0f;
        return Color.HSBtoRGB(hue, saturation, brightness);
    }

    private void saveImage() {
        try {
            renderMandelbrot(); //render before new size chosen by the user fix
            File outputfile = new File("mandelbrot.png");
            ImageIO.write(image, "png", outputfile);
            Logger.log("Image saved to " + outputfile.getAbsolutePath(), LogLevel.Success);
        } catch (IOException e) {
            e.printStackTrace();
            Logger.log("Failed to save the image.", LogLevel.Error);
        }
    }

    @Override
    public void paint(Graphics g) {
        long frameStartTime = System.currentTimeMillis();
        super.paint(g);
        g.drawImage(image, 0, 0, null);
        long frameEndTime = System.currentTimeMillis();

        // Limit to 60 FPS
        long frameDuration = frameEndTime - frameStartTime;
        long targetFrameDuration = 1000 / TARGET_FPS;
        if (frameDuration < targetFrameDuration) {
            try {
                Thread.sleep(targetFrameDuration - frameDuration);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static void runPerformanceTests() {
        int startSize = 1000;
        int maxSize = 10000;
        String csvFile = "mandelbrot_results.csv";

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
            writer.println("width,height,parallel");

            for (int size = startSize; size <= maxSize; size += 1000) {
                MandelbrotViewer viewer = new MandelbrotViewer();
                viewer.width = size;
                viewer.height = size;
                long startTime = System.currentTimeMillis();
                viewer.renderMandelbrot();
                long endTime = System.currentTimeMillis();
                long renderTime =    endTime - startTime;

                Logger.log("Rendered " + size + "x" + size + " in " + renderTime + " ms", LogLevel.Status);
                writer.println(size + "," + size + "," + renderTime);
            }
        } catch (IOException e) {
            e.printStackTrace();
            Logger.log("Error writing CSV file.", LogLevel.Error);
        }
    }

    private void processChunk(Chunk chunk, int width, int height) {
        for (int x = chunk.startX; x < chunk.endX; x++) {
            for (int y = chunk.startY; y < chunk.endY; y++) {
                double real = xMin + x * (xMax - xMin) / width;
                double imag = yMin + y * (yMax - yMin) / height;
                Complex c = new Complex(real, imag);
                int color = computePoint(c);
                image.setRGB(x, y, color);
            }
        }
    }


    private static class Chunk {
        final int startX, startY, endX, endY;

        public Chunk(int startX, int startY, int endX, int endY) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
        }
    }

    public static void main(String[] args) {


        SwingUtilities.invokeLater(() -> {
            for (String arg : args) {
                if (arg.equalsIgnoreCase("--nongui")) { //no gui render launch option
                    headlessMode = true;
                } else if (arg.equalsIgnoreCase("--test")) { //render testing option
                    runTests = true;
                }
            }

            if (headlessMode) {
                long startTime = System.currentTimeMillis();
                MandelbrotViewer viewer = new MandelbrotViewer();
                viewer.renderMandelbrot();
                long endTime = System.currentTimeMillis();
                Logger.log("Non-GUI speed: " + (endTime - startTime) + " ms", LogLevel.Status);
                System.exit(0);
            }

            if (runTests) {
                runPerformanceTests();
                Logger.log("Performance tests completed. Results saved to mandelbrot_results.csv", LogLevel.Status);
                System.exit(0); //the file will keep overwriting itself if run n times in a row
            } else {
                MandelbrotViewer viewer = new MandelbrotViewer();
                viewer.setVisible(true); //some problem here with the nongui setting
            }
        });
    }

    static class Complex {
        private final double real;
        private final double imag;

        public Complex(double real, double imag) {
            this.real = real;
            this.imag = imag;
        }

        public Complex add(Complex other) {
            return new Complex(this.real + other.real, this.imag + other.imag);
        }

        public Complex multiply(Complex other) {
            return new Complex(
                    this.real * other.real - this.imag * other.imag,
                    this.real * other.imag + this.imag * other.real
            );
        }

        public double abs() {
            return Math.sqrt(real * real + imag * imag);
        }
    }
}