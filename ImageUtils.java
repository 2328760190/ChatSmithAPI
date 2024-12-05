import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ImageUtils {

    /**
     * 检查并缩放图像，如果图像的宽度或高度超过指定的最大值。
     *
     * @param imageData 原始图像数据
     * @param maxWidth  最大宽度
     * @param maxHeight 最大高度
     * @return 处理后的图像数据
     * @throws IOException 如果图像读取或写入失败
     * @throws ImageReadException 如果图像读取失败
     */
    public static byte[] checkAndScaleImage(byte[] imageData, int maxWidth, int maxHeight) throws IOException, ImageReadException {
        // 获取图像信息
        ImageInfo imageInfo = Imaging.getImageInfo(imageData);

        // 获取图像宽度和高度
        int originalWidth = imageInfo.getWidth();
        int originalHeight = imageInfo.getHeight();

        // 判断是否需要缩放
        if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
            // 不需要缩放
            return imageData;
        }

        // 计算缩放比例
        double widthRatio = (double) maxWidth / originalWidth;
        double heightRatio = (double) maxHeight / originalHeight;
        double scalingFactor = Math.min(widthRatio, heightRatio);

        int newWidth = (int) (originalWidth * scalingFactor);
        int newHeight = (int) (originalHeight * scalingFactor);

        // 使用 Imaging 读取图像为 BufferedImage
        BufferedImage originalImage = Imaging.getBufferedImage(imageData);

        // 获取原始图像的像素数据
        int[] originalPixels = new int[originalWidth * originalHeight];
        originalImage.getRGB(0, 0, originalWidth, originalHeight, originalPixels, 0, originalWidth);

        // 创建目标像素数组
        int[] scaledPixels = new int[newWidth * newHeight];

        double xRatio = (double) originalWidth / newWidth;
        double yRatio = (double) originalHeight / newHeight;

        for (int y = 0; y < newHeight; y++) {
            double srcY = y * yRatio;
            int yFloor = (int) Math.floor(srcY);
            int yCeil = Math.min(yFloor + 1, originalHeight - 1);
            double yDiff = srcY - yFloor;

            for (int x = 0; x < newWidth; x++) {
                double srcX = x * xRatio;
                int xFloor = (int) Math.floor(srcX);
                int xCeil = Math.min(xFloor + 1, originalWidth - 1);
                double xDiff = srcX - xFloor;

                int a = originalPixels[yFloor * originalWidth + xFloor];
                int b = originalPixels[yFloor * originalWidth + xCeil];
                int c = originalPixels[yCeil * originalWidth + xFloor];
                int d = originalPixels[yCeil * originalWidth + xCeil];

                int interpolatedPixel = bilinearInterpolate(a, b, c, d, xDiff, yDiff);
                scaledPixels[y * newWidth + x] = interpolatedPixel;
            }
        }

        // 创建缩放后的 BufferedImage
        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB); // 使用 TYPE_INT_RGB 以兼容 JPEG
        scaledImage.setRGB(0, 0, newWidth, newHeight, scaledPixels, 0, newWidth);

        // 使用标准 Java ImageIO 写回图像数据
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaledImage, "JPEG", baos); // 指定使用 "JPEG" 格式
        return baos.toByteArray();
    }

    /**
     * 双线性插值算法。
     *
     * @param a     左上角像素
     * @param b     右上角像素
     * @param c     左下角像素
     * @param d     右下角像素
     * @param xDiff X 方向差值
     * @param yDiff Y 方向差值
     * @return 插值后的像素值
     */
    private static int bilinearInterpolate(int a, int b, int c, int d, double xDiff, double yDiff) {
        int alpha = ((a >> 24) & 0xFF);
        int red = ((a >> 16) & 0xFF);
        int green = ((a >> 8) & 0xFF);
        int blue = (a & 0xFF);

        int redB = ((b >> 16) & 0xFF);
        int greenB = ((b >> 8) & 0xFF);
        int blueB = (b & 0xFF);

        int redC = ((c >> 16) & 0xFF);
        int greenC = ((c >> 8) & 0xFF);
        int blueC = (c & 0xFF);

        int redD = ((d >> 16) & 0xFF);
        int greenD = ((d >> 8) & 0xFF);
        int blueD = (d & 0xFF);

        double redInterp = red * (1 - xDiff) * (1 - yDiff) +
                redB * xDiff * (1 - yDiff) +
                redC * (1 - xDiff) * yDiff +
                redD * xDiff * yDiff;

        double greenInterp = green * (1 - xDiff) * (1 - yDiff) +
                greenB * xDiff * (1 - yDiff) +
                greenC * (1 - xDiff) * yDiff +
                greenD * xDiff * yDiff;

        double blueInterp = blue * (1 - xDiff) * (1 - yDiff) +
                blueB * xDiff * (1 - yDiff) +
                blueC * (1 - xDiff) * yDiff +
                blueD * xDiff * yDiff;

        int redFinal = clamp((int) Math.round(redInterp), 0, 255);
        int greenFinal = clamp((int) Math.round(greenInterp), 0, 255);
        int blueFinal = clamp((int) Math.round(blueInterp), 0, 255);

        return (alpha << 24) | (redFinal << 16) | (greenFinal << 8) | blueFinal;
    }

    /**
     * 将数值限制在指定范围内。
     *
     * @param value 数值
     * @param min   最小值
     * @param max   最大值
     * @return 限制后的数值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
