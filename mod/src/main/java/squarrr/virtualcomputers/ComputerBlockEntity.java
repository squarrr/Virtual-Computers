package squarrr.virtualcomputers;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public abstract class ComputerBlockEntity extends BlockEntity {
    private static final String KEY = "machine";

    private @Nullable String machineId;

    protected ComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public @Nullable String machineId() {
        return machineId;
    }

    public void adoptFrom(ItemStack stack) {
        String carried = stack.get(VcComponents.MACHINE_ID.get());
        machineId = carried != null && !carried.isBlank() ? carried : UUID.randomUUID().toString();
        if (level != null && level.isClientSide()) {
            PlacedMachines.add(machineId);
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public ItemStack withIdentity(ItemStack stack) {
        if (machineId != null) {
            stack.set(VcComponents.MACHINE_ID.get(), machineId);
        }
        return stack;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            PlacedMachines.add(machineId);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            PlacedMachines.remove(machineId);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String stored = input.getStringOr(KEY, "");
        machineId = stored.isBlank() ? null : stored;
        if (level != null && level.isClientSide()) {
            PlacedMachines.add(machineId);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (machineId != null) {
            output.putString(KEY, machineId);
        }
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}
