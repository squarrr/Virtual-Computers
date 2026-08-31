package squarrr.virtualcomputers;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class DesktopItem extends BlockItem {
    public DesktopItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide()) {
            BlockPos existing = DesktopClaim.existing(level);
            if (existing != null) {
                if (context.getPlayer() != null) {
                    context.getPlayer().sendSystemMessage(Component.literal(
                            "You already have a desktop, at " + existing.getX() + " "
                            + existing.getY() + " " + existing.getZ()
                            + ". Break it before placing another.")
                            .withStyle(ChatFormatting.RED));
                }
                return InteractionResult.FAIL;
            }
        }
        return super.place(context);
    }
}
