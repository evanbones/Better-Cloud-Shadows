package com.evandev.better_cloud_shadows.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import org.joml.Matrix4f;

final class DhRenderState {

    private static final long STALE_NANOS = 500_000_000L;

    private static final float[] inverseViewProjection = new float[16];
    private static long stamp;
    private static boolean bound;

    private DhRenderState() {
    }

    static void bind() {
        if (bound) return;
        bound = true;
        DhApi.events.bind(DhApiBeforeRenderCleanupEvent.class, new Listener());
    }

    static int depthTextureId() {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return 0;

        DhApiResult<Integer> result = proxy.getDhDepthTextureGlId();
        if (result == null || !result.success || result.payload == null) return 0;
        return result.payload;
    }

    static synchronized boolean inverseViewProjection(Matrix4f out) {
        if (stamp == 0 || System.nanoTime() - stamp > STALE_NANOS) return false;
        out.set(inverseViewProjection).transpose();
        return true;
    }

    static synchronized void clear() {
        stamp = 0;
    }

    private static synchronized void store(DhApiRenderParam param) {
        System.arraycopy(param.dhInverseMvmProjectionMatrix.getValuesAsArray(), 0, inverseViewProjection, 0, 16);
        stamp = System.nanoTime();
    }

    private static final class Listener extends DhApiBeforeRenderCleanupEvent {
        @Override
        public void beforeCleanup(DhApiEventParam<DhApiRenderParam> event) {
            if (event == null || event.value == null) return;
            store(event.value);
        }
    }
}
