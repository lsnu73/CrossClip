package com.crossclip.app.tile

import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.crossclip.app.service.SyncForegroundService

@RequiresApi(Build.VERSION_CODES.N)
class SendClipTileService : TileService() {

    override fun onClick() {
        super.onClick()
        SyncForegroundService.instance?.sendCurrentClipboardManual()
    }
}
