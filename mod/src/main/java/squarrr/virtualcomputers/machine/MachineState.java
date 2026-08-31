package squarrr.virtualcomputers.machine;

public enum MachineState {
    OFF("Off"),

    SLEEPING("Sleeping"),

    STARTING("Booting"),

    RUNNING("Live"),

    INSTALLING("Installing"),

    FETCHING("Fetching"),

    FAILED("Failed");

    private final String label;

    MachineState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isPowered() {
        return this == STARTING || this == RUNNING || this == INSTALLING;
    }

    public boolean wantsPowerOn() {
        return this == OFF || this == SLEEPING || this == FAILED;
    }

    public boolean isBusy() {
        return this == FETCHING;
    }
}
