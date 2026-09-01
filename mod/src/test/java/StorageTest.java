import squarrr.virtualcomputers.vm.Checksum;
import squarrr.virtualcomputers.vm.Cleanup;
import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.ImageFetch;
import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.Provisioning;
import squarrr.virtualcomputers.vm.StorageReport;
import squarrr.virtualcomputers.vm.Templates;
import squarrr.virtualcomputers.vm.VmStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StorageTest {
    private static int checks;
    private static int failures;

    private static final int BASE_BYTE = 0x5A;
    private static final int OVERLAY_BYTE = 0xAA;

    private static final long REGION = 1L << 20;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("vc-storage-test");
        System.setProperty("vc.data", scratch.toString());
        try {
            registryParses();
            registryRejectsWhatItShould();
            seedsAreValidYaml();
            knowsWhenItWouldDownload();
            nothingIsWrittenOutsideWithoutAsking(scratch);
            theStorageReportAddsUp(scratch);
            sizesReadTheWayPeopleWriteThem();
            checksumsWork();

            if (Hypervisor.diagnose().qemuImg() == null) {
                System.out.println("\n  qemu-img not found: the storage checks need it and were"
                        + " skipped.\n  Everything above ran.");
            } else {
                overlaysAreIndependent(scratch);
                aTemplateIsFrozen(scratch);
                aDiskKnowsWhatItWasClonedFrom(scratch);
                deletingRemovesExactlyWhatItSaid(scratch);
                if (Boolean.getBoolean("vc.network")) {
                    theRealThingActuallyDownloads();
                } else {
                    System.out.println("\n  -Dvc.network=true also fetches a real image from its"
                            + " vendor and checks the\n  pinned checksum against the actual bytes."
                            + " Off by default: it is a 275 MB download.");
                }
            }
        } finally {
            deleteTree(scratch);
        }

        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void registryParses() {
        section("The registry");

        check("five OS boxes ship", OsRegistry.BOXED.size() == 5, "got " + OsRegistry.BOXED);
        for (String id : List.of("windows_10", "windows_11", "linux", "tv", "custom")) {
            check(id + " is one of them", OsRegistry.BOXED.contains(id), "missing");
        }

        for (OsEntry.Kind kind : OsEntry.Kind.values()) {
            boolean shipped = OsRegistry.all().values().stream().anyMatch(e -> e.kind() == kind);
            check("something that ships is a " + kind.name().toLowerCase(), shipped,
                    "no built-in entry has this kind");
        }

        for (OsEntry entry : OsRegistry.all().values()) {
            if (entry.kind() == OsEntry.Kind.RECIPE) {
                check(entry.id() + " builds on an entry that exists",
                        OsRegistry.get(entry.base()) != null, "no entry called " + entry.base());
                check(entry.id() + " has a script to run",
                        entry.provision() != null && !entry.provision().isBlank(), "empty");
            }
        }

        for (String id : OsRegistry.BUILT_IN) {
            OsEntry entry = OsRegistry.get(id);
            check(id + " parses", entry != null, "did not load - see the log above");
            if (entry == null) {
                continue;
            }
            check(id + " keeps its id", id.equals(entry.id()), "got " + entry.id());
            check(id + " has a name", entry.name() != null && !entry.name().isBlank(), "blank");
            if (!entry.isLocalOnly()) {
                check(id + " is fetched over https", entry.source().url().startsWith("https://"),
                        entry.source().url());
                check(id + " pins a checksum",
                        entry.source().checksum() != null && !entry.source().checksum().isBlank(),
                        "none");
                check(id + " declares its download size", entry.source().size() > 0,
                        "size " + entry.source().size());
            } else if (entry.kind() != OsEntry.Kind.RECIPE) {
                check(id + " says what to supply", entry.localHint() != null, "no local_hint");
            }
        }

        OsEntry tv = OsRegistry.get("tv");
        check("an archived entry's media name loses the .gz",
                tv != null && tv.mediaFileName().endsWith(".img"),
                tv == null ? "no entry" : tv.mediaFileName());
        OsEntry eleven = OsRegistry.get("windows_11");
        check("Windows 11 asks for Secure Boot firmware",
                eleven != null && eleven.firmware() == OsEntry.Firmware.UEFI_SECURE,
                eleven == null ? "no entry" : String.valueOf(eleven.firmware()));
        OsEntry ten = OsRegistry.get("windows_10");
        check("Windows 10 does not",
                ten != null && ten.firmware() != OsEntry.Firmware.UEFI_SECURE,
                ten == null ? "no entry" : String.valueOf(ten.firmware()));

    }

    private static void registryRejectsWhatItShould() {
        section("What the registry refuses");

        check("the baseline entry these are variants of is accepted", !rejects("""
                {"id":"xx","name":"X","kind":"installer","local_hint":"bring your own"}"""),
                "the baseline is refused, so no rejection below proves anything");

        check("a url with no checksum is refused", rejects("""
                {"id":"xx","name":"X","kind":"installer",
                 "source":{"url":"https://example.invalid/a.iso"}}"""), "it was accepted");

        check("an unknown digest algorithm is refused", rejects("""
                {"id":"xx","name":"X","kind":"installer",
                 "source":{"url":"https://example.invalid/a.iso","checksum":"crc32:deadbeef"}}"""),
                "it was accepted");

        check("a plain-http url is refused", rejects("""
                {"id":"xx","name":"X","kind":"installer",
                 "source":{"url":"http://example.invalid/a.iso","checksum":"sha256:%s"}}"""
                .formatted("0".repeat(64))), "it was accepted");

        check("an id with capitals is refused", rejects("""
                {"id":"Windows","name":"X","kind":"installer"}"""), "it was accepted");

        check("a one-character id is refused", rejects("""
                {"id":"x","name":"X","kind":"installer"}"""), "it was accepted");

        check("an unknown kind is refused", rejects("""
                {"id":"xx","name":"X","kind":"floppy"}"""), "it was accepted");

        check("a recipe with no base is refused", rejects("""
                {"id":"xx","name":"X","kind":"recipe"}"""), "it was accepted");

        check("an absurd disk size is refused", rejects("""
                {"id":"xx","name":"X","kind":"installer","disk_gb":99999}"""), "it was accepted");

        check("a missing name is refused", rejects("""
                {"id":"xx","kind":"installer"}"""), "it was accepted");

        check("a properly sourced entry is accepted", !rejects("""
                {"id":"xx","name":"X","kind":"installer",
                 "source":{"url":"https://example.invalid/a.iso","checksum":"sha256:%s","size":1}}"""
                .formatted("a".repeat(64))), "a valid entry was refused");
    }

    private static boolean rejects(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            OsEntry.parse(object, "test");
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void seedsAreValidYaml() throws Exception {
        section("A recipe's seed");

        JsonObject json = JsonParser.parseString("""
                {"id":"probe","name":"Probe","kind":"recipe","base":"linux",
                 "provision":"echo 'it worked' > /tmp/x\\nexport A=$B # note: colon"}""")
                .getAsJsonObject();
        OsEntry recipe = OsEntry.parse(json, "test");
        Path seed = Provisioning.seedFor(recipe, "machine-1");

        check("the seed directory exists", Files.isDirectory(seed), String.valueOf(seed));
        check("cloud-init's meta-data is there", Files.isRegularFile(seed.resolve("meta-data")),
                "missing");

        String userData = Files.readString(seed.resolve("user-data"), StandardCharsets.UTF_8);
        check("user-data announces itself as cloud-config",
                userData.startsWith("#cloud-config"), userData.lines().findFirst().orElse(""));
        check("the guest is told to power off when the script finishes, which is how we know",
                userData.contains("mode: poweroff"), "no power_state");

        check("an apostrophe is escaped YAML's way, by doubling",
                userData.contains("''it worked''"), "not doubled");
        check("and NOT the shell's way, which would end the scalar early",
                !userData.contains("'\"'\"'"), "shell escaping leaked into YAML");

        check("the whole thing parses as YAML", parsesAsYaml(userData), "it does not");

        if (Hypervisor.diagnose().qemuImg() != null) {
            String label = volumeLabelOf(Provisioning.blockOptions(seed));
            check("the seed volume is really labelled \"cidata\", read back from its boot sector",
                    "cidata".equals(label), "got \"" + label + "\"");
        }

        String drive = Provisioning.driveArgument(seed);
        check("the -drive form names the same label", drive.contains("file.label=cidata"), drive);
        check("the -drive form names the vvfat driver", drive.contains("file.driver=vvfat"), drive);
    }

    private static String volumeLabelOf(String blockOptions) {
        try {
            Path dump = Files.createTempFile("vc-seed", ".bin");
            try {
                Process process = new ProcessBuilder(
                        Hypervisor.diagnose().qemuImg().toString(), "dd", "--image-opts",
                        "if=" + blockOptions, "of=" + dump, "bs=512", "count=200")
                        .redirectErrorStream(true).start();
                String said = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                if (process.waitFor() != 0) {
                    return "qemu-img said: " + said.replace("\n", " | ");
                }
                byte[] head = Files.readAllBytes(dump);

                int lba = (head[0x1BE + 8] & 0xFF) | (head[0x1BE + 9] & 0xFF) << 8
                        | (head[0x1BE + 10] & 0xFF) << 16 | (head[0x1BE + 11] & 0xFF) << 24;
                int at = lba * 512 + 0x2B;
                if (at + 11 > head.length) {
                    return "(partition beyond the dump)";
                }
                return new String(head, at, 11, StandardCharsets.ISO_8859_1).trim();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException | RuntimeException e) {
            return "(" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted)";
        }
    }

    private static boolean parsesAsYaml(String userData) {
        for (String line : userData.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("- [")) {
                continue;
            }
            int quotes = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) != '\'') {
                    continue;
                }
                if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                quotes++;
            }
            if (quotes % 2 != 0) {
                System.out.println("      unbalanced: " + trimmed);
                return false;
            }
        }
        return true;
    }

    private static void knowsWhenItWouldDownload() {
        section("Knowing before downloading");

        for (String id : List.of("windows_10", "windows_11", "custom", "tv_kodi")) {
            OsEntry entry = OsRegistry.get(id);
            check(id + " needs no download, so it never prompts",
                    entry != null && ImageFetch.mediaReady(entry), "reported as needing a download");
        }
        for (String id : List.of("linux", "tv", "debian_cloud")) {
            OsEntry entry = OsRegistry.get(id);
            check(id + " has nothing cached here, so it would prompt first",
                    entry != null && !ImageFetch.mediaReady(entry), "reported as already present");
        }
    }

    private static void theStorageReportAddsUp(Path scratch) throws Exception {
        section("The storage report");

        Files.createDirectories(scratch.resolve("disks"));
        Files.createDirectories(scratch.resolve("templates"));
        Files.createDirectories(scratch.resolve("images"));
        Files.createDirectories(scratch.resolve("consoles"));
        Files.write(scratch.resolve("disks/aaa-111.qcow2"), new byte[3000]);
        Files.write(scratch.resolve("disks/bbb-222.qcow2"), new byte[5000]);
        Files.write(scratch.resolve("templates/linux.qcow2"), new byte[7000]);
        Files.write(scratch.resolve("images/thing.iso"), new byte[11000]);
        Files.writeString(scratch.resolve("images/thing.iso.verified"), "sha256:abc");
        Files.writeString(scratch.resolve("consoles/aaa-111.log"),
                "Debian GNU/Linux 13 tellytubby ttyS0\n\ntellytubby login: ");

        Files.writeString(scratch.resolve("disks/aaa-111.qcow2.os"), "linux");
        try (java.io.RandomAccessFile big =
                     new java.io.RandomAccessFile(scratch.resolve("disks/ccc-333.qcow2").toFile(), "rw")) {
            big.setLength(40L * 1024 * 1024);
        }

        StorageReport report = StorageReport.collect();
        StorageReport.Location here = report.locations().stream()
                .filter(StorageReport.Location::current).findFirst().orElse(null);
        check("this instance appears in the report", here != null, "not found");
        if (here == null) {
            return;
        }
        check("every machine disk is listed", here.disks().size() == 3,
                "got " + here.disks().size());
        check("a marker beside the disk says which operating system is on it",
                here.disks().stream().anyMatch(d -> "linux".equals(d.os())),
                "got " + here.disks().stream().map(StorageReport.Disk::os).toList());
        check("an .os marker is not mistaken for a machine",
                here.disks().stream().noneMatch(d -> d.id().endsWith(".qcow2")),
                "a marker was listed as a disk");
        check("a disk with something on it is not reported as empty just because nothing named it",
                here.disks().stream().anyMatch(d -> d.os() == null && d.installed()),
                "the big unnamed disk was called empty");
        check("and a disk with nothing on it still reads as empty",
                here.disks().stream().anyMatch(d -> d.os() == null && !d.installed()),
                "no empty disk found");
        check("a hostname is read out of the guest's own console",
                here.disks().stream().anyMatch(d -> "tellytubby".equals(d.hostname())),
                "got " + here.disks().stream().map(StorageReport.Disk::hostname).toList());
        check("a machine with no console log has no hostname to show",
                here.disks().stream().anyMatch(d -> d.hostname() == null), "all had one");
        check("each disk reports when it was added",
                here.disks().stream().allMatch(d -> d.addedEpochMs() > 0), "a timestamp was missing");

        long disksBytes = here.disks().stream().mapToLong(StorageReport.Disk::bytes).sum();
        long groupBytes = here.groups().stream().mapToLong(StorageReport.Group::bytes).sum();
        check("machine disks add up", disksBytes == 8000 + 40L * 1024 * 1024,
                "got " + disksBytes);
        check("installed operating systems are counted on their own",
                groupBytes("Installed operating systems", here) == 7000,
                "got " + groupBytes("Installed operating systems", here));
        check("downloaded media is counted on its own",
                groupBytes("Downloaded media", here) == 11000,
                "got " + groupBytes("Downloaded media", here));
        check("the .verified marker is not counted as media",
                here.groups().stream().filter(g -> g.label().contains("media"))
                        .allMatch(g -> g.count() == 1), "the marker was counted");
        check("the location total is machines plus everything else",
                here.total() == disksBytes + groupBytes,
                here.total() + " vs " + (disksBytes + groupBytes));
        check("the grand total is at least this location", report.total() >= here.total(),
                "got " + report.total());
    }

    private static void nothingIsWrittenOutsideWithoutAsking(Path scratch) throws Exception {
        section("Looking in other instances is opt-in");

        Path index = scratch.resolve("elsewhere/locations.txt");
        System.setProperty("vc.locations", index.toString());
        Files.deleteIfExists(index);

        check("nobody has been asked yet", VmStore.sharing() == VmStore.Sharing.UNSET,
                String.valueOf(VmStore.sharing()));
        check("and until they are, only this instance is looked at",
                VmStore.knownLocations().size() == 1, "got " + VmStore.knownLocations());
        check("no file has appeared outside this instance's own folder", !Files.exists(index),
                index + " was created without being asked for");

        VmStore.setSharing(false);
        check("saying no is remembered", VmStore.sharing() == VmStore.Sharing.DECLINED,
                String.valueOf(VmStore.sharing()));
        check("saying no still writes nothing outside", !Files.exists(index), "a file appeared");
        check("and still shows only this instance", VmStore.knownLocations().size() == 1,
                "got " + VmStore.knownLocations());

        VmStore.setSharing(true);
        check("saying yes is remembered", VmStore.sharing() == VmStore.Sharing.ALLOWED,
                String.valueOf(VmStore.sharing()));
        check("only then does the list exist", Files.exists(index), "no file");
        check("and it holds this instance's folder",
                Files.readString(index, StandardCharsets.UTF_8).contains(scratch.toString()),
                Files.readString(index, StandardCharsets.UTF_8).trim());

        VmStore.setSharing(false);
        System.clearProperty("vc.locations");
    }

    private static long groupBytes(String label, StorageReport.Location location) {
        return location.groups().stream().filter(g -> g.label().equals(label))
                .mapToLong(StorageReport.Group::bytes).sum();
    }

    private static void checksumsWork() throws Exception {
        section("Checksums");

        Path file = Files.createTempFile("vc-sum", ".bin");
        Files.writeString(file, "virtual computers", StandardCharsets.UTF_8);

        Checksum sha256 = Checksum.parse("sha256:"
                + "b1e2b1f4c0e2e6c0c0d1e4bbd0c9e9d4a3f5b8c7e6d5a4b3c2d1e0f9a8b7c6d5", "test");
        String actual = sha256.of(file, null);
        check("a digest is 64 hex characters", actual.matches("[0-9a-f]{64}"), actual);
        check("a wrong digest does not match", !sha256.matches(file, null), "it matched");

        Checksum recomputed = Checksum.parse("sha256:" + actual, "test");
        check("the digest it computed is the digest it accepts", recomputed.matches(file, null),
                "round trip failed");

        check("a bare hex string is taken as sha-256",
                "SHA-256".equals(Checksum.parse("0".repeat(64), "test").algorithm()), "no");
        check("sha1 is accepted, for vendors who publish nothing better",
                "SHA-1".equals(Checksum.parse("sha1:" + "0".repeat(40), "test").algorithm()), "no");
        check("a digest of the wrong length is refused", refusesChecksum("sha256:abc"), "accepted");
        check("a non-hex digest is refused", refusesChecksum("sha256:" + "z".repeat(64)), "accepted");

        Files.deleteIfExists(file);
    }

    private static boolean refusesChecksum(String value) {
        try {
            Checksum.parse(value, "test");
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void overlaysAreIndependent(Path scratch) throws Exception {
        section("Overlays are independent");

        Path raw = scratch.resolve("base.raw");
        byte[] filler = new byte[(int) REGION];
        java.util.Arrays.fill(filler, (byte) BASE_BYTE);
        Files.write(raw, filler);

        Path template = Templates.adopt(raw, "test_os");
        check("a downloaded image becomes a template", Files.isRegularFile(template),
                "no template at " + template);

        String baseDigestBefore = Checksum.parse("sha256:" + "0".repeat(64), "t").of(template, null);

        Path a = scratch.resolve("a.qcow2");
        Path b = scratch.resolve("b.qcow2");
        Path c = scratch.resolve("c.qcow2");
        for (Path overlay : List.of(a, b, c)) {
            Templates.createOverlay(overlay, template, 1);
        }
        check("three machines cloned", Files.isRegularFile(a) && Files.isRegularFile(b)
                && Files.isRegularFile(c), "one is missing");

        long overlayBytes = Files.size(a);
        long templateBytes = Files.size(template);
        check("an overlay costs almost nothing next to its base",
                overlayBytes * 4 < templateBytes,
                String.format("overlay %d B, template %d B", overlayBytes, templateBytes));

        check("every overlay starts out reading the base",
                qemuIo(a, "read -P 0x%02X 0 %d".formatted(BASE_BYTE, REGION))
                        && qemuIo(b, "read -P 0x%02X 0 %d".formatted(BASE_BYTE, REGION))
                        && qemuIo(c, "read -P 0x%02X 0 %d".formatted(BASE_BYTE, REGION)),
                "an overlay did not read the base's bytes");

        check("one machine writes to its own disk",
                qemuIo(a, "write -P 0x%02X 0 %d".formatted(OVERLAY_BYTE, REGION)),
                "the write failed");

        check("that machine reads back what it wrote",
                qemuIo(a, "read -P 0x%02X 0 %d".formatted(OVERLAY_BYTE, REGION)),
                "it did not read back");

        check("THE OTHER MACHINES DO NOT SEE IT",
                qemuIo(b, "read -P 0x%02X 0 %d".formatted(BASE_BYTE, REGION))
                        && qemuIo(c, "read -P 0x%02X 0 %d".formatted(BASE_BYTE, REGION)),
                "an overlay saw another machine's write - the storage model is broken");

        String baseDigestAfter = Checksum.parse("sha256:" + "0".repeat(64), "t").of(template, null);
        check("and the template itself is untouched", baseDigestBefore.equals(baseDigestAfter),
                "the backing file changed under its overlays");
    }

    private static void aTemplateIsFrozen(Path scratch) throws Exception {
        section("A template is frozen");

        Path template = Templates.forEntry("test_os");
        check("a template is read-only once written", !template.toFile().canWrite(),
                "it is writable, and writing to it would corrupt every machine cloned from it");

        Path other = scratch.resolve("other.raw");
        Files.write(other, new byte[1024]);
        boolean refused;
        try {
            Templates.freeze(other, "test_os");
            refused = false;
        } catch (IOException expected) {
            refused = true;
        }
        check("a second template cannot overwrite the first", refused, "it was replaced");

        check("thawing is possible, for the one case that needs it", Templates.thaw("test_os"),
                "could not thaw");
        template.toFile().setReadOnly();
    }

    private static void aDiskKnowsWhatItWasClonedFrom(Path scratch) throws Exception {
        section("A disk knows what it came from");

        Path disk = VmStore.diskFor("some-machine");
        Files.createDirectories(disk.getParent());
        Templates.createOverlay(disk, Templates.forEntry("test_os"), 1);
        check("a cloned machine reports its operating system",
                "test_os".equals(VmStore.osOf("some-machine")),
                "got " + VmStore.osOf("some-machine"));

        VmStore.ensureDisk("plain-machine", 1);
        check("a machine with no template reports none",
                VmStore.osOf("plain-machine") == null,
                "got " + VmStore.osOf("plain-machine"));
    }

    private static void sizesReadTheWayPeopleWriteThem() {
        section("Sizes read the way people write them");

        check("under a kilobyte is still bytes", "1023 B".equals(Templates.human(1023)),
                Templates.human(1023));
        check("an empty machine disk is not six digits of bytes",
                "193 KB".equals(Templates.human(197120)), Templates.human(197120));
        check("a firmware store reads in kilobytes",
                "528 KB".equals(Templates.human(540672)), Templates.human(540672));
        check("exactly one megabyte is a megabyte, not 1024 KB",
                "1.0 MB".equals(Templates.human(1L << 20)), Templates.human(1L << 20));
        check("a template reads in megabytes",
                "952 MB".equals(Templates.human(998309888L)), Templates.human(998309888L));
        check("an installed machine reads in gigabytes",
                "7.1 GB".equals(Templates.human(7614169088L)), Templates.human(7614169088L));
        check("and a whole library reads in terabytes",
                "1.5 TB".equals(Templates.human(1649267441664L)), Templates.human(1649267441664L));
        check("no size lands in a unit it has no business in",
                java.util.stream.LongStream.of(1023L, 1024L, 1L << 20, 1L << 30, 1L << 40)
                        .mapToObj(Templates::human)
                        .map(text -> text.replaceAll("[0-9. ]", "")).distinct().count() == 5,
                "two different magnitudes printed the same unit");
    }

    private static void deletingRemovesExactlyWhatItSaid(Path scratch) throws Exception {
        section("Deleting");

        Files.createDirectories(scratch.resolve("consoles"));
        Files.createDirectories(scratch.resolve("firmware"));
        Files.write(scratch.resolve("disks/ddd-444.qcow2"), new byte[9000]);
        Files.writeString(scratch.resolve("disks/ddd-444.qcow2.os"), "linux");
        Files.writeString(scratch.resolve("consoles/ddd-444.log"), "boot");
        Files.write(scratch.resolve("firmware/ddd-444_VARS.fd"), new byte[100]);
        Path bystander = scratch.resolve("disks/eee-555.qcow2");
        Files.write(bystander, new byte[64]);

        Cleanup.Plan machine = Cleanup.forMachine(scratch, "ddd-444", "ddd-444");
        check("a computer takes its disk, its marker, its console log and its firmware with it",
                machine.files().size() == 4, "got " + machine.files());
        check("and says how much that frees before anything is deleted",
                machine.bytes() == 9109, "got " + machine.bytes());
        check("deleting it frees what it said", Cleanup.delete(machine) == 9109, "wrong total");
        check("the computer is gone", !Files.exists(scratch.resolve("disks/ddd-444.qcow2"))
                && !Files.exists(scratch.resolve("consoles/ddd-444.log"))
                && !Files.exists(scratch.resolve("firmware/ddd-444_VARS.fd")), "something survived");
        check("and nothing else was touched", Files.isRegularFile(bystander), "a bystander went too");

        Files.write(scratch.resolve("images/keeper.iso"), new byte[2048]);
        Files.writeString(scratch.resolve("images/keeper.iso.verified"), "sha256:abc");
        StorageReport.Group media = groupOf(StorageReport.Kind.MEDIA);
        StorageReport.Item keeper = media.items().stream()
                .filter(i -> i.name().equals("keeper.iso")).findFirst().orElseThrow();
        Cleanup.Plan download = Cleanup.forItem(media, keeper);
        check("deleting a download takes the checksum marker beside it",
                download.files().size() == 2, "got " + download.files());
        Cleanup.delete(download);
        check("both are gone", !Files.exists(scratch.resolve("images/keeper.iso"))
                && !Files.exists(scratch.resolve("images/keeper.iso.verified")), "one survived");

        StorageReport.Group templates = groupOf(StorageReport.Kind.TEMPLATES);
        StorageReport.Item testOs = templates.items().stream()
                .filter(i -> i.name().equals("test_os")).findFirst().orElseThrow();
        Cleanup.Plan needed = Cleanup.forItem(templates, testOs);
        check("A TEMPLATE WITH A MACHINE ON IT IS REFUSED", !needed.allowed(),
                "it offered to delete a template that machines are running on");
        check("and the refusal counts the machines that would break",
                needed.refusal() != null && needed.refusal().startsWith("One computer is"),
                String.valueOf(needed.refusal()));
        check("refusing is not advice, it is enforced", refused(needed), "delete went ahead anyway");
        check("the template is still there", Files.isRegularFile(Templates.forEntry("test_os")),
                "it was deleted");

        Cleanup.delete(Cleanup.forMachine(scratch, "some-machine", "some-machine"));
        Cleanup.Plan free = Cleanup.forItem(templates, testOs);
        check("once nothing is built on it, the template can go", free.allowed(),
                String.valueOf(free.refusal()));
        check("and a read-only template really is deleted", Cleanup.delete(free) > 0
                && !Files.exists(Templates.forEntry("test_os")),
                "the frozen file survived deletion");
    }

    private static boolean refused(Cleanup.Plan plan) {
        try {
            Cleanup.delete(plan);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static StorageReport.Group groupOf(StorageReport.Kind kind) {
        return StorageReport.collect().locations().stream()
                .filter(StorageReport.Location::current)
                .flatMap(l -> l.groups().stream())
                .filter(g -> g.kind() == kind)
                .findFirst().orElseThrow();
    }

    private static void theRealThingActuallyDownloads() throws Exception {
        section("The real thing (network)");

        for (OsEntry entry : OsRegistry.all().values()) {
            if (entry.isLocalOnly()) {
                continue;
            }
            long started = System.currentTimeMillis();
            try {
                Path fetched = ImageFetch.ensureMedia(entry, (done, total) -> { });
                check(entry.id() + ": the pinned checksum matches the bytes at the pinned URL",
                        Files.isRegularFile(fetched),
                        "fetch returned a path that is not a file");
                System.out.printf("          %s in %d s%n", Templates.human(Files.size(fetched)),
                        (System.currentTimeMillis() - started) / 1000);
            } catch (IOException e) {
                check(entry.id() + ": the pinned checksum matches the bytes at the pinned URL",
                        false, e.getMessage().replace("\n", " "));
            }
        }

        OsEntry tv = OsRegistry.get("tv");
        if (tv == null || tv.isLocalOnly()) {
            return;
        }
        Path media = ImageFetch.ensureMedia(tv, (done, total) -> { });
        check("the gzip was unpacked into a disk image", Files.isRegularFile(media)
                && media.getFileName().toString().endsWith(".img"), String.valueOf(media));
        check("the unpacked image is substantially larger than the download",
                Files.size(media) > tv.source().size(),
                Files.size(media) + " vs " + tv.source().size());

        Path template = Templates.adopt(media, tv.id());
        check("it becomes a frozen template", Files.isRegularFile(template)
                && !template.toFile().canWrite(), "not frozen");

        check("the template is smaller than the raw image it came from",
                Files.size(template) < Files.size(media),
                Templates.human(Files.size(template)) + " vs " + Templates.human(Files.size(media)));

        Path machine = VmStore.diskFor("tv-machine");
        Files.createDirectories(machine.getParent());
        Templates.createOverlay(machine, template, tv.diskGb());
        check("a television clones it in an instant", Files.isRegularFile(machine)
                && Files.size(machine) * 100 < Files.size(template),
                Templates.human(Files.size(machine)) + " overlay on "
                        + Templates.human(Files.size(template)));
    }

    private static boolean qemuIo(Path image, String command) throws Exception {
        Path qemuIo = Hypervisor.diagnose().qemuImg().resolveSibling(
                Hypervisor.diagnose().qemuImg().getFileName().toString().replace("qemu-img", "qemu-io"));
        if (!Files.isExecutable(qemuIo)) {
            System.out.println("      (qemu-io not found beside qemu-img; skipping)");
            return true;
        }
        List<String> argv = new ArrayList<>(List.of(qemuIo.toString(), "-f", "qcow2",
                "-c", command, image.toAbsolutePath().toString()));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int status = process.waitFor();

        boolean verifyFailed = output.contains("Pattern verification failed");
        if (status != 0 || verifyFailed) {
            System.out.println("      qemu-io: " + output.trim().replace("\n", "\n      "));
        }
        return status == 0 && !verifyFailed;
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void section(String title) {
        System.out.println("\n" + title);
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what + " -- " + detail);
        }
    }
}
