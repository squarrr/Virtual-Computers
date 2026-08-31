package squarrr.virtualcomputers;

public enum DeviceKind {
    LAPTOP(true, true),

    DESKTOP(true, false),

    SCREEN(false, true);

    private final boolean computer;
    private final boolean hasScreen;

    DeviceKind(boolean computer, boolean hasScreen) {
        this.computer = computer;
        this.hasScreen = hasScreen;
    }

    public boolean isComputer() {
        return computer;
    }

    public boolean hasScreen() {
        return hasScreen;
    }

    public boolean hasMediaSlot() {
        return computer;
    }

    public boolean sleepsWhenBroken() {
        return this == LAPTOP;
    }
}
