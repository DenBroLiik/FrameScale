package by.xopilot.framescale;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

final class GpuTimer {
    private static final int SLOTS = 12;
    private final int[] start = new int[SLOTS], end = new int[SLOTS];
    private final boolean[] pending = new boolean[SLOTS];
    private int active = -1;
    private boolean initialized;
    boolean begin(FrameStats stats) {
        if (!GL.getCapabilities().OpenGL33) return false;
        if (!initialized) {
            for (int i=0;i<SLOTS;i++) { start[i]=GL15.glGenQueries(); end[i]=GL15.glGenQueries(); }
            initialized=true;
        }
        for (int i=0;i<SLOTS;i++) {
            if (pending[i] && GL15.glGetQueryObjecti(end[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0
                    && GL15.glGetQueryObjecti(start[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long a=GL33.glGetQueryObjectui64(start[i],GL15.GL_QUERY_RESULT);
                long b=GL33.glGetQueryObjectui64(end[i],GL15.GL_QUERY_RESULT);
                stats.add((b-a)/1_000_000.0);
                pending[i]=false;
            }
        }
        active=-1;
        for (int i=0;i<SLOTS;i++) if (!pending[i]) {
            GL33.glQueryCounter(start[i], GL33.GL_TIMESTAMP); active=i; break;
        }
        return true;
    }
    void end() {
        if (active < 0) return;
        GL33.glQueryCounter(end[active], GL33.GL_TIMESTAMP);
        pending[active]=true; active=-1;
    }
    void close() {
        for(int i=0;i<SLOTS;i++) {
            if(start[i]!=0) GL15.glDeleteQueries(start[i]);
            if(end[i]!=0) GL15.glDeleteQueries(end[i]);
            start[i]=0; end[i]=0; pending[i]=false;
        }
        active=-1; initialized=false;
    }
}
