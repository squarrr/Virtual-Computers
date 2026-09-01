package squarrr.virtualcomputers;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(VirtualComputers.MODID)
public class VirtualComputers {
    public static final String MODID = "virtualcomputers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final String DESKTOP_MACHINE_ID = "desktop";

    public static DisplayInteraction interaction = DisplayInteraction.NONE;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredBlock<ScreenBlock> SCREEN_BLOCK =
            BLOCKS.registerBlock("screen", ScreenBlock::new,
                    p -> p.mapColor(MapColor.COLOR_BLACK).strength(1.5F).noOcclusion());

    public static final DeferredItem<BlockItem> SCREEN_ITEM =
            ITEMS.registerSimpleBlockItem("screen", SCREEN_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN_BE =
            BLOCK_ENTITIES.register("screen",
                    () -> new BlockEntityType<>(ScreenBlockEntity::new, SCREEN_BLOCK.get()));

    public static final DeferredBlock<LaptopBlock> LAPTOP_BLOCK =
            BLOCKS.registerBlock("laptop", LaptopBlock::new,
                    p -> p.mapColor(MapColor.COLOR_GRAY).instabreak().noOcclusion());

    public static final DeferredItem<BlockItem> LAPTOP_ITEM =
            ITEMS.registerSimpleBlockItem("laptop", LAPTOP_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LaptopBlockEntity>> LAPTOP_BE =
            BLOCK_ENTITIES.register("laptop",
                    () -> new BlockEntityType<>(LaptopBlockEntity::new, LAPTOP_BLOCK.get()));

    public static final DeferredBlock<DesktopBlock> DESKTOP_BLOCK =
            BLOCKS.registerBlock("desktop", DesktopBlock::new,
                    p -> p.mapColor(MapColor.COLOR_GRAY).strength(2.5F).noOcclusion());

    public static final DeferredItem<DesktopItem> DESKTOP_ITEM =
            ITEMS.registerItem("desktop", props -> new DesktopItem(DESKTOP_BLOCK.get(), props),
                    () -> new Item.Properties().useBlockDescriptionPrefix());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DesktopBlockEntity>> DESKTOP_BE =
            BLOCK_ENTITIES.register("desktop",
                    () -> new BlockEntityType<>(DesktopBlockEntity::new, DESKTOP_BLOCK.get()));

    private static DeferredItem<OsBoxItem> osBox(String name, String entryId) {
        return ITEMS.registerItem(name, props -> new OsBoxItem(props, entryId),
                () -> new Item.Properties().stacksTo(1));
    }

    public static final DeferredItem<OsBoxItem> WINDOWS_10_OS = osBox("windows_10_os", "windows_10");
    public static final DeferredItem<OsBoxItem> WINDOWS_11_OS = osBox("windows_11_os", "windows_11");
    public static final DeferredItem<OsBoxItem> LINUX_OS = osBox("linux_os", "linux");
    public static final DeferredItem<OsBoxItem> TV_OS = osBox("tv_os", "tv");
    public static final DeferredItem<OsBoxItem> CUSTOM_OS = osBox("custom_os", "custom");

    public VirtualComputers(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        VcComponents.COMPONENTS.register(modEventBus);
        LOGGER.info("Virtual Computers: phase 3, operating systems as items");
    }
}
