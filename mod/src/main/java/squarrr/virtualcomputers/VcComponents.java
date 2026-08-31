package squarrr.virtualcomputers;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class VcComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, VirtualComputers.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> MACHINE_ID =
            COMPONENTS.registerComponentType("machine_id",
                    builder -> builder.persistent(Codec.STRING).networkSynchronized(
                            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> IMAGE_ID =
            COMPONENTS.registerComponentType("image_id",
                    builder -> builder.persistent(Codec.STRING).networkSynchronized(
                            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    private VcComponents() {
    }
}
