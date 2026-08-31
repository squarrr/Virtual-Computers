package vncspike;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record PixelFormat(
        int bitsPerPixel,
        int depth,
        boolean bigEndian,
        boolean trueColour,
        int redMax,
        int greenMax,
        int blueMax,
        int redShift,
        int greenShift,
        int blueShift) {
    public static final PixelFormat STANDARD_32BPP =
            new PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0);

    public static PixelFormat read(DataInputStream in) throws IOException {
        int bpp = in.readUnsignedByte();
        int depth = in.readUnsignedByte();
        boolean big = in.readUnsignedByte() != 0;
        boolean trueColour = in.readUnsignedByte() != 0;
        int rMax = in.readUnsignedShort();
        int gMax = in.readUnsignedShort();
        int bMax = in.readUnsignedShort();
        int rShift = in.readUnsignedByte();
        int gShift = in.readUnsignedByte();
        int bShift = in.readUnsignedByte();
        in.skipNBytes(3);
        return new PixelFormat(bpp, depth, big, trueColour, rMax, gMax, bMax, rShift, gShift, bShift);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(bitsPerPixel);
        out.writeByte(depth);
        out.writeByte(bigEndian ? 1 : 0);
        out.writeByte(trueColour ? 1 : 0);
        out.writeShort(redMax);
        out.writeShort(greenMax);
        out.writeShort(blueMax);
        out.writeByte(redShift);
        out.writeByte(greenShift);
        out.writeByte(blueShift);
        out.write(new byte[3]);
    }

    public boolean isStandard32bpp() {
        return bitsPerPixel == 32 && trueColour && !bigEndian
                && redMax == 255 && greenMax == 255 && blueMax == 255
                && redShift == 16 && greenShift == 8 && blueShift == 0;
    }

    @Override
    public String toString() {
        return bitsPerPixel + "bpp depth" + depth
                + (bigEndian ? " BE" : " LE")
                + (trueColour ? " truecolour" : " palette")
                + " r" + redShift + " g" + greenShift + " b" + blueShift;
    }
}
