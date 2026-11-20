package dev.lusalhi.lumynoid.app

import androidx.core.content.FileProvider

/**
 * The manifest registers this subclass, never `androidx.core.content.FileProvider` itself: AndroidX documents that
 * declaring the base class directly is not reliable and crashes on some devices.
 */
class LumynoidFileProvider : FileProvider()
