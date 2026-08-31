package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Provisioning {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private static final String LABEL = "cidata";

    private Provisioning() {
    }

    public static Path seedFor(OsEntry recipe, String machineId) throws IOException {
        Path seed = VmStore.root().resolve("provision").resolve(recipe.id());
        Files.createDirectories(seed);

        Files.writeString(seed.resolve("meta-data"),
                "instance-id: " + recipe.id() + "-" + machineId + "\n"
                + "local-hostname: " + recipe.id().replace('_', '-') + "\n",
                StandardCharsets.UTF_8);

        String script = recipe.provision() == null ? "true" : recipe.provision();
        String userData = """
                #cloud-config
                # Written by the Virtual Computers mod to build the "%s" template.
                # It runs once, on first boot, and then powers the machine off - which is how the
                # mod knows the build finished, because nothing else about a script is visible from
                # outside the guest.
                package_update: true
                runcmd:
                %s
                power_state:
                  mode: poweroff
                  message: provisioning finished
                  timeout: 30
                  condition: true
                """.formatted(recipe.id(), indent(script));
        Files.writeString(seed.resolve("user-data"), userData, StandardCharsets.UTF_8);
        LOGGER.info("[os] seeded the {} recipe at {}", recipe.id(), seed);
        return seed;
    }

    public static String driveArgument(Path seed) {
        return "if=virtio,readonly=on," + blockOptions(seed);
    }

    public static String blockOptions(Path seed) {
        String dir = seed.toAbsolutePath().toString().replace('\\', '/').replace(",", ",,");
        return "driver=raw,file.driver=vvfat"
                + ",file.dir=" + dir
                + ",file.label=" + LABEL
                + ",file.rw=false";
    }

    private static String indent(String script) {
        StringBuilder out = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            out.append("  - ['sh', '-c', '").append(line.trim().replace("'", "''")).append("']\n");
        }
        return out.isEmpty() ? "  - ['true']\n" : out.toString();
    }
}
