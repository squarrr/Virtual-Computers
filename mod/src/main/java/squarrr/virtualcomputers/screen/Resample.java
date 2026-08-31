package squarrr.virtualcomputers.screen;

public final class Resample {
    private int sourceWidth;
    private int sourceHeight;
    private int width;
    private int height;

    private int[] destinationOfColumn = new int[0];
    private int[] destinationOfRow = new int[0];
    private int[] columnCount = new int[0];
    private int[] rowCount = new int[0];
    private int[] accumulatedRed = new int[0];
    private int[] accumulatedGreen = new int[0];
    private int[] accumulatedBlue = new int[0];

    public boolean matches(int sourceWidth, int sourceHeight, int width, int height) {
        return this.sourceWidth == sourceWidth && this.sourceHeight == sourceHeight
                && this.width == width && this.height == height;
    }

    public void configure(int sourceWidth, int sourceHeight, int width, int height) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.width = width;
        this.height = height;

        destinationOfColumn = new int[sourceWidth];
        columnCount = new int[width];
        for (int x = 0; x < width; x++) {
            int start = x * sourceWidth / width;
            int end = Math.min(sourceWidth, Math.max(start + 1, (x + 1) * sourceWidth / width));
            columnCount[x] = end - start;
            for (int sx = start; sx < end; sx++) {
                destinationOfColumn[sx] = x;
            }
        }
        destinationOfRow = new int[sourceHeight];
        rowCount = new int[height];
        for (int y = 0; y < height; y++) {
            int start = y * sourceHeight / height;
            int end = Math.min(sourceHeight, Math.max(start + 1, (y + 1) * sourceHeight / height));
            rowCount[y] = end - start;
            for (int sy = start; sy < end; sy++) {
                destinationOfRow[sy] = y;
            }
        }
        accumulatedRed = new int[width];
        accumulatedGreen = new int[width];
        accumulatedBlue = new int[width];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void boxDownscale(int[] source, int[] destination) {
        int current = -1;
        for (int sourceY = 0; sourceY < sourceHeight; sourceY++) {
            int y = destinationOfRow[sourceY];
            if (y != current) {
                if (current >= 0) {
                    emitRow(destination, current);
                }
                java.util.Arrays.fill(accumulatedRed, 0);
                java.util.Arrays.fill(accumulatedGreen, 0);
                java.util.Arrays.fill(accumulatedBlue, 0);
                current = y;
            }

            int base = sourceY * sourceWidth;
            for (int sourceX = 0; sourceX < sourceWidth; sourceX++) {
                int argb = source[base + sourceX];
                int x = destinationOfColumn[sourceX];
                accumulatedRed[x] += argb >> 16 & 0xFF;
                accumulatedGreen[x] += argb >> 8 & 0xFF;
                accumulatedBlue[x] += argb & 0xFF;
            }
        }
        if (current >= 0) {
            emitRow(destination, current);
        }
    }

    private void emitRow(int[] destination, int y) {
        int rows = rowCount[y];
        int offset = y * width;
        for (int x = 0; x < width; x++) {
            float inverse = 1.0F / (columnCount[x] * rows);
            destination[offset + x] = 0xFF000000
                    | (int) (accumulatedRed[x] * inverse) << 16
                    | (int) (accumulatedGreen[x] * inverse) << 8
                    | (int) (accumulatedBlue[x] * inverse);
        }
    }
}
