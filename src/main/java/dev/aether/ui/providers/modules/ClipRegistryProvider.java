package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.clip.ClipManager;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class ClipRegistryProvider extends AbstractModulesRegistryProvider {
    public ClipRegistryProvider() {
        super(13);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Clip Recorder",
                        "Records the last seconds of gameplay when a failsafe or ban happens")
                .add(new ToggleSetting("Clip On Failsafe",
                        () -> AetherConfig.CLIP_ON_FAILSAFE.get(),
                        v -> {
                            AetherConfig.CLIP_ON_FAILSAFE.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Clip On Ban",
                        () -> AetherConfig.CLIP_ON_BAN.get(),
                        v -> {
                            AetherConfig.CLIP_ON_BAN.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Clip Length", 5, 100,
                        () -> (float) AetherConfig.CLIP_LENGTH_SECONDS.get(),
                        v -> {
                            AetherConfig.CLIP_LENGTH_SECONDS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" s"))
                .add(new TextSetting("FFmpeg Path", "leave blank to use system PATH",
                        () -> AetherConfig.CLIP_FFMPEG_PATH.get(),
                        v -> {
                            AetherConfig.CLIP_FFMPEG_PATH.set(v == null ? "" : v.trim());
                            AetherConfig.save();
                        }))
                .add(new ActionSetting("Open Clips Folder", ClipManager::openClipsFolder));

        return MainGUIRegistry.toggleSubTab(
                "Clip Recorder",
                "Records the last seconds of gameplay when a failsafe or ban happens",
                () -> AetherConfig.CLIP_ENABLED.get(),
                ClipRegistryProvider::setClipEnabled,
                List.of(group));
    }

    private static void setClipEnabled(boolean enabled) {
        AetherConfig.CLIP_ENABLED.set(enabled);
        AetherConfig.save();
        ClipManager.syncFromConfig();
    }
}
