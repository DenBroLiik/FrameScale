#include "frame_contract.hpp"
#include <cassert>
#include <limits>
#include <cstdio>
int main(){
    using namespace fs;
    assert(valid({1920,1200}));assert(!valid({0,1200}));assert(!valid({8192,8192}));
    Optimal a{{1280,800},{960,600},{1536,960},{1920,1200}};
    assert(a.valid());a.minimum={1400,900};assert(!a.valid());
    a.minimum={960,600};a.maximum={2000,1200};assert(!a.valid());
    FrameInfo frame{.jitterX=.25f,.jitterY=-.25f,.preExposure=1,.frameMs=12.5f};
    assert(frame.valid());frame.preExposure=0;assert(!frame.valid());
    frame.preExposure=1;frame.jitterX=std::numeric_limits<float>::quiet_NaN();assert(!frame.valid());
    frame.jitterX=0;frame.frameMs=0;assert(!frame.valid());
    std::puts("DLSS frame contract PASS");
}
