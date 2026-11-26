package by.xopilot.framescale;

/** Conservative comparability check; movement is a proxy, not proof of identical GPU work. */
record SceneGuard(double x,double y,double z,float yaw,float pitch) {
    boolean comparable(SceneGuard other) {
        if(other==null) return false;
        double dx=x-other.x,dy=y-other.y,dz=z-other.z;
        double angle=Math.abs(Math.IEEEremainder((double)yaw-other.yaw,360));
        return dx*dx+dy*dy+dz*dz<=64 && angle<=35 && Math.abs(pitch-other.pitch)<=25;
    }
}
