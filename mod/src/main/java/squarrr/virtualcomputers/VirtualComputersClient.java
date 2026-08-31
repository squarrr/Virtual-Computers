package squarrr.virtualcomputers;

import squarrr.virtualcomputers.harness.RenderHarness;
import squarrr.virtualcomputers.input.Focus;
import squarrr.virtualcomputers.input.VcKeys;
import squarrr.virtualcomputers.machine.Machines;
import squarrr.virtualcomputers.screen.DisplayRenderer;
import squarrr.virtualcomputers.screen.LaptopRenderer;
import squarrr.virtualcomputers.screen.ScreenRenderer;
import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.Templates;
import squarrr.virtualcomputers.vm.VmStore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@Mod(value = VirtualComputers.MODID, dist = Dist.CLIENT)
public class VirtualComputersClient {
    public VirtualComputersClient(ModContainer container) {
        VirtualComputers.interaction = new Focus();
    }

    @EventBusSubscriber(modid = VirtualComputers.MODID, value = Dist.CLIENT)
    public static class Events {
        @SubscribeEvent
        static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(VirtualComputers.SCREEN_BE.get(), ScreenRenderer::new);
            event.registerBlockEntityRenderer(VirtualComputers.LAPTOP_BE.get(), LaptopRenderer::new);

            Hypervisor.diagnose().log();
            VirtualComputers.LOGGER.info("[vm] data directory {}", VmStore.root().toAbsolutePath());

            VirtualComputers.LOGGER.info("[os] {} operating systems; templates ready: {}",
                    OsRegistry.all().size(),
                    Templates.installed().isEmpty() ? "none yet" : Templates.installed());
            for (String line : OsRegistry.describe()) {
                VirtualComputers.LOGGER.info("[os]   {}", line);
            }
            if (!Hypervisor.supportsTpm()) {
                VirtualComputers.LOGGER.info("[os] this QEMU has no TPM support compiled in, so"
                        + " Windows 11 cannot be installed; everything else is unaffected");
            }

            String vnc = System.getProperty("vc.vnc");
            if (vnc != null) {
                VirtualComputers.LOGGER.info(
                        "[vm] -Pvc.vnc={} is set, so machines attach to that endpoint and this mod "
                        + "will not start or stop anything", vnc);
            }
        }

        @SubscribeEvent
        static void registerKeys(RegisterKeyMappingsEvent event) {
            VcKeys.register(event);
        }

        @SubscribeEvent
        static void registerCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(RenderHarness.command());
        }

        @SubscribeEvent
        static void onRenderFrame(RenderFrameEvent.Pre event) {
            if (!Machines.isEmpty()) {
                Machines.prepareFrame(System.currentTimeMillis());
            }
        }

        @SubscribeEvent
        static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            DisplayRenderer.forgetAll();
            Machines.closeAll();
        }

        @SubscribeEvent
        static void onGameShuttingDown(GameShuttingDownEvent event) {
            Machines.persistAllAndWait();
        }
    }
}
