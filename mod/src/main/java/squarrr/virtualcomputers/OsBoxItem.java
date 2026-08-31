package squarrr.virtualcomputers;

import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.Templates;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

public class OsBoxItem extends Item {
    private final String defaultEntryId;

    public OsBoxItem(Properties properties, String defaultEntryId) {
        super(properties);
        this.defaultEntryId = defaultEntryId;
    }

    public String entryId(ItemStack stack) {
        String bound = stack.get(VcComponents.IMAGE_ID.get());
        return bound != null && !bound.isBlank() ? bound : defaultEntryId;
    }

    public @Nullable OsEntry entry(ItemStack stack) {
        return OsRegistry.get(entryId(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> lines, TooltipFlag flag) {
        OsEntry entry = entry(stack);
        if (entry == null) {
            lines.accept(Component.literal("No entry called \"" + entryId(stack) + "\"")
                    .withStyle(ChatFormatting.RED));
            lines.accept(Component.literal("Put a matching .json in the os folder.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        lines.accept(Component.literal(entry.name()).withStyle(ChatFormatting.GRAY));

        if (Templates.exists(entry.id())) {
            lines.accept(Component.literal("Installed once already - a machine takes seconds")
                    .withStyle(ChatFormatting.GREEN));
        } else if (entry.isLocalOnly()) {
            lines.accept(Component.literal("Needs media you supply yourself")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            lines.accept(Component.literal("Downloads "
                    + Templates.human(entry.source().size()) + " the first time")
                    .withStyle(ChatFormatting.GOLD));
        }

        if (flag.isAdvanced()) {
            lines.accept(Component.literal(entry.id() + " - "
                    + entry.kind().name().toLowerCase() + ", " + entry.diskGb() + " GB")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
