#include "interop_image.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    FsInteropImage image={0};
    assert(fs_interop_valid_extent(1920,1200));
    assert(fs_interop_valid_extent(4096,4096));
    assert(!fs_interop_valid_extent(4097,4096));
    assert(!fs_interop_valid_extent(0,1));
    assert(!fs_interop_valid_extent(UINT32_MAX,UINT32_MAX));
    assert(!fs_interop_create(NULL,VK_NULL_HANDLE,VK_NULL_HANDLE,1,1));
    assert(!fs_interop_create(&image,VK_NULL_HANDLE,VK_NULL_HANDLE,1,1));
    assert(!fs_interop_gl_wait(&image));
    assert(!fs_interop_gl_signal(&image));
    fs_interop_destroy(&image);
    fs_interop_destroy(&image);
    fs_interop_destroy(NULL);
    puts("Interop API argument and empty lifecycle tests PASS");
    return 0;
}
