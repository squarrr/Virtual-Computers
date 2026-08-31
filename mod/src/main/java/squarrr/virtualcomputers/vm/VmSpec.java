package squarrr.virtualcomputers.vm;

public record VmSpec(int vcpu, int memoryMb, int diskGb) {
    public static final VmSpec LAPTOP = new VmSpec(2, 4096, 16);

    public static final VmSpec DESKTOP = new VmSpec(2, 4096, 32);

    @Override
    public String toString() {
        return vcpu + " vCPU, " + memoryMb + " MB, " + diskGb + " GB";
    }
}
