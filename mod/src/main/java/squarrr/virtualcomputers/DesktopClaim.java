package squarrr.virtualcomputers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

public class DesktopClaim extends SavedData {
    public static final Codec<DesktopClaim> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("pos").forGetter(claim -> Optional.ofNullable(claim.pos))
    ).apply(instance, DesktopClaim::new));

    public static final SavedDataType<DesktopClaim> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(VirtualComputers.MODID, "desktop_claim"),
            DesktopClaim::new, CODEC);

    private @Nullable BlockPos pos;

    public DesktopClaim() {
    }

    private DesktopClaim(Optional<BlockPos> pos) {
        this.pos = pos.orElse(null);
    }

    private static DesktopClaim of(Level level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public static @Nullable BlockPos existing(Level level) {
        if (level.isClientSide() || level.getServer() == null) {
            return null;
        }
        DesktopClaim claim = of(level);
        BlockPos claimed = claim.pos;
        if (claimed == null) {
            return null;
        }
        ServerLevel overworld = level.getServer().overworld();
        if (overworld.isLoaded(claimed)
                && !overworld.getBlockState(claimed).is(VirtualComputers.DESKTOP_BLOCK.get())) {
            claim.pos = null;
            claim.setDirty();
            VirtualComputers.LOGGER.info("[desktop] claim at {} was stale; cleared", claimed);
            return null;
        }
        return claimed;
    }

    public static void claim(Level level, BlockPos pos) {
        if (level.isClientSide() || level.getServer() == null) {
            return;
        }
        DesktopClaim claim = of(level);
        claim.pos = pos.immutable();
        claim.setDirty();
    }

    public static void release(Level level, BlockPos pos) {
        if (level.isClientSide() || level.getServer() == null) {
            return;
        }
        DesktopClaim claim = of(level);
        if (pos.equals(claim.pos)) {
            claim.pos = null;
            claim.setDirty();
        }
    }
}
