package squarrr.virtualcomputers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public interface DisplayInteraction {
    DisplayInteraction NONE = new DisplayInteraction() {
        @Override
        public boolean use(DeviceKind kind, String machineId, BlockPos pos, Direction facing, Vec3 hit) {
            return false;
        }

        @Override
        public void broken(DeviceKind kind, String machineId) {
        }

        @Override
        public boolean insertMedia(DeviceKind kind, String machineId, String entryId, boolean finish) {
            return false;
        }
    };

    boolean insertMedia(DeviceKind kind, String machineId, String entryId, boolean finish);

    boolean use(DeviceKind kind, String machineId, BlockPos pos, Direction facing, Vec3 hit);

    void broken(DeviceKind kind, String machineId);
}
