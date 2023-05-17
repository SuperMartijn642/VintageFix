package org.embeddedt.vintagefix.mixin.texture_stitching;

import net.minecraft.client.renderer.texture.PngSizeInfo;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.ProgressManager;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Logger;
import org.embeddedt.vintagefix.VintageFix;
import org.embeddedt.vintagefix.stitcher.TextureCache;
import org.embeddedt.vintagefix.stitcher.WrongDimensionException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@Mixin(TextureMap.class)
public abstract class MixinTextureMap {
    @Shadow
    protected abstract ResourceLocation getResourceLocation(TextureAtlasSprite p_184396_1_);

    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapRegisteredSprites;
    @Shadow
    @Final
    private String basePath;
    private static final String TEXTURE_LOADER_CORE = "loadTexture(Lnet/minecraft/client/renderer/texture/Stitcher;Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/util/ResourceLocation;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraftforge/fml/common/ProgressManager$ProgressBar;II)I";

    private static final IResource EMPTY_META_RESOURCE = new IResource() {
        @Override
        public ResourceLocation getResourceLocation() {
            return new ResourceLocation("stub");
        }

        @Override
        public InputStream getInputStream() {
            return null;
        }

        @Override
        public boolean hasMetadata() {
            return false;
        }

        @Nullable
        @Override
        public <T extends IMetadataSection> T getMetadata(String sectionName) {
            return null;
        }

        @Override
        public String getResourcePackName() {
            return null;
        }

        @Override
        public void close() throws IOException {
        }
    };

    @ModifyConstant(method = "loadTextureAtlas", constant = @Constant(stringValue = "Texture stitching"))
    private String correctMessage(String original) {
        return "Texture loading";
    }

    /* Accelerate texture loading using similar strategy to ModernFix - drop PngSizeInfo entirely, defer height/width checks to later */
    @Redirect(method = TEXTURE_LOADER_CORE, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/PngSizeInfo;makeFromResource(Lnet/minecraft/client/resources/IResource;)Lnet/minecraft/client/renderer/texture/PngSizeInfo;"))
    private PngSizeInfo skipPngLoad(IResource resource) {
        return null;
    }

    @Redirect(method = TEXTURE_LOADER_CORE, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/IResourceManager;getResource(Lnet/minecraft/util/ResourceLocation;)Lnet/minecraft/client/resources/IResource;"))
    private IResource skipResourceLoad(IResourceManager manager, ResourceLocation location) {
        return EMPTY_META_RESOURCE;
    }

    @Redirect(method = "generateMipmaps", at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", ordinal = 0))
    private void skipErrorForWrongDimension(Logger logger, String msg, Object o1, Object o2, IResourceManager resourceManager, final TextureAtlasSprite texture) {
        ResourceLocation resourcelocation = this.getResourceLocation(texture);
        net.minecraftforge.fml.client.FMLClientHandler.instance().trackBrokenTexture(resourcelocation, ((RuntimeException)o2).getMessage());
    }

    @Redirect(method = "generateMipmaps", at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", ordinal = 1))
    private void skipErrorForMissing(Logger logger, String msg, Object o1, Object o2, IResourceManager resourceManager, final TextureAtlasSprite texture) {
        ResourceLocation resourcelocation = this.getResourceLocation(texture);
        net.minecraftforge.fml.client.FMLClientHandler.instance().trackMissingTexture(resourcelocation);
    }

    private static final Executor TEXTURE_LOADER_POOL = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private static final AtomicInteger loadedCount = new AtomicInteger(0);

    @Inject(method = "loadTextureAtlas", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/ProgressManager;push(Ljava/lang/String;I)Lnet/minecraftforge/fml/common/ProgressManager$ProgressBar;"))
    private void preloadTextures(IResourceManager resourceManager, CallbackInfo ci) {
        /* parallel texture load go brr */
        loadedCount.set(0);
        int numSubmittedSprites = 0;
        for(Map.Entry<String, TextureAtlasSprite> entry : mapRegisteredSprites.entrySet()) {
            TEXTURE_LOADER_POOL.execute(() -> {
                try {
                    ResourceLocation fileLocation = this.getResourceLocation(entry.getValue());
                    if(!entry.getValue().hasCustomLoader(resourceManager, fileLocation)) {
                        try(IResource resource = resourceManager.getResource(fileLocation)) {
                            BufferedImage image = TextureUtil.readBufferedImage(resource.getInputStream());
                            TextureCache.textureLoadingCache.put(fileLocation, image);
                        }
                    }
                } catch(IOException | RuntimeException ignored) {
                } finally {
                    loadedCount.incrementAndGet();
                }
            });
            numSubmittedSprites++;
        }
        ProgressManager.ProgressBar bar = ProgressManager.push("Texture preloading", 1);
        long timeToBlock = TimeUnit.MILLISECONDS.toNanos(30);
        while(loadedCount.get() < numSubmittedSprites) {
            LockSupport.parkNanos(timeToBlock);
        }
        bar.step("done");
        ProgressManager.pop(bar);
    }

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void clearAtlasCache(IResourceManager manager, CallbackInfo ci) {
        TextureCache.textureLoadingCache.clear();
    }
}
