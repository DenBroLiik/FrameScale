#pragma once
#include <cstdint>
#include <cmath>

namespace fs {
struct Extent {
    std::uint32_t w=0,h=0;
    constexpr bool operator==(const Extent&) const = default;
};
constexpr bool valid(Extent e) {
    return e.w>0&&e.h>0&&e.w<=8192&&e.h<=8192
        &&std::uint64_t(e.w)*e.h<=16777216;
}
constexpr bool fits(Extent a,Extent b) {return a.w<=b.w&&a.h<=b.h;}
struct Optimal {
    Extent recommended,minimum,maximum,output;
    constexpr bool valid() const {
        return fs::valid(recommended)&&fs::valid(minimum)&&fs::valid(maximum)&&fs::valid(output)
            &&fits(minimum,recommended)&&fits(recommended,maximum)&&fits(maximum,output);
    }
};
struct FrameInfo {
    float jitterX=0,jitterY=0,preExposure=1,frameMs=0;
    bool reset=false;
    bool valid() const {
        return std::isfinite(jitterX)&&std::isfinite(jitterY)&&std::abs(jitterX)<=1&&std::abs(jitterY)<=1
            &&std::isfinite(preExposure)&&preExposure>0
            &&std::isfinite(frameMs)&&frameMs>0&&frameMs<=1000;
    }
};
}
