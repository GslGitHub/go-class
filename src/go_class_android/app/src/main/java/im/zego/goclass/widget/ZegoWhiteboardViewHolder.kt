package im.zego.goclass.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.easypermission.Permission
import im.zego.goclass.CONFERENCE_ID
import im.zego.goclass.sdk.ZegoSDKManager
import im.zego.goclass.tool.PermissionHelper
import im.zego.goclass.tool.ToastUtils
import im.zego.goclass.upload.FileUtil
import im.zego.zegodocs.*
import im.zego.zegowhiteboard.ZegoWhiteboardManager
import im.zego.zegowhiteboard.ZegoWhiteboardView
import im.zego.zegowhiteboard.callback.IZegoWhiteboardViewScaleListener
import im.zego.zegowhiteboard.callback.IZegoWhiteboardViewScrollListener
import im.zego.goclass.R
import im.zego.goclass.classroom.ClassRoomManager
import im.zego.zegowhiteboard.ZegoWhiteboardConstants
import im.zego.zegowhiteboard.model.ZegoWhiteboardViewModel
import kotlin.math.round

/**
 * 白板容器,一个文件对应一个容器，包含docsview和白板
 * 如果是excel，一个docsView,可能会对应多个白板
 * 其他类型的文件，一个docsView,只有一个白板
 */
class ZegoWhiteboardViewHolder : FrameLayout {
    val TAG = "WhiteboardViewHolder"

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var whiteboardViewList: MutableList<ZegoWhiteboardView> = mutableListOf()
    private var zegoDocsView: ZegoDocsView? = null
    private var fileLoadSuccessed = false
    private var whiteboardViewAddFinished = false

    private var internalScrollListener: IZegoWhiteboardViewScrollListener =
        IZegoWhiteboardViewScrollListener { horizontalPercent, verticalPercent ->
            outScrollListener?.onScroll(horizontalPercent, verticalPercent)
        }
    private var outScrollListener: IZegoWhiteboardViewScrollListener? = null
    var toolsView: WhiteboardToolsView? = null

    /**
     * 当前显示的白板ID
     */
    var currentWhiteboardID = 0L
        set(value) {
            field = value
            Log.d(TAG, "set currentWhiteboardID:${value}")
            var selectedView: ZegoWhiteboardView? = null
            whiteboardViewList.forEach {
                val viewModel = it.getWhiteboardViewModel()
                if (viewModel.whiteboardID == value) {
                    it.visibility = View.VISIBLE
                    selectedView = it
                } else {
                    it.visibility = View.GONE
                }
                Log.d(
                    TAG,
                    "whiteboardViewList: ${viewModel.whiteboardID}:${viewModel.fileInfo.fileName}"
                )
            }
            selectedView?.let {
                Log.d(TAG, "selectedView:${it.getWhiteboardViewModel().fileInfo.fileName}")
                val viewModel = it.getWhiteboardViewModel()
                if (zegoDocsView != null && isExcel()) {
                    val fileName = viewModel.fileInfo.fileName
                    val sheetIndex = getExcelSheetNameList().indexOf(fileName)
                    zegoDocsView!!.switchSheet(sheetIndex, IZegoDocsViewLoadListener { loadResult ->
                        Log.d(TAG, "loadResult = $loadResult")
                        if (loadResult == 0) {
                            Log.i(
                                TAG, "switchSheet,sheetIndex:$sheetIndex," +
                                        "visibleSize:${zegoDocsView!!.getVisibleSize()}" +
                                        "contentSize:${zegoDocsView!!.getContentSize()}"
                            )
                            viewModel.aspectWidth = zegoDocsView!!.getContentSize().width
                            viewModel.aspectHeight = zegoDocsView!!.getContentSize().height

                            connectDocsViewAndWhiteboardView(it)

                            zegoDocsView!!.scaleDocsView(
                                it.getScaleFactor(),
                                it.getScaleOffsetX(),
                                it.getScaleOffsetY()
                            )
                        }
                    })
                }
            }
            enableUserOperation(ClassRoomManager.me().sharable)
            toolsView?.let {
                Log.i(TAG, "set currentWhiteboardID,setCanDraw:${!it.isDragSelected()}")
                this.setCanDraw(!it.isDragSelected())
            }
        }

    fun setDocsScaleEnable(selected: Boolean) {
        zegoDocsView?.setScaleEnable(!selected)
//        zegoDocsView?.scaleEnable = (!selected)
    }

    private var currentWhiteboardView: ZegoWhiteboardView?
        private set(value) {}
        get() {
            return whiteboardViewList.firstOrNull {
                it.getWhiteboardViewModel().whiteboardID == currentWhiteboardID
            }
        }

    fun hasWhiteboardID(whiteboardID: Long): Boolean {
        val firstOrNull = whiteboardViewList.firstOrNull {
            it.getWhiteboardViewModel().whiteboardID == whiteboardID
        }
        return firstOrNull != null
    }

    fun getWhiteboardIDList(): List<Long> {
        return whiteboardViewList.map { it.getWhiteboardViewModel().whiteboardID }
    }

    fun isFileWhiteboard(): Boolean {
        return getFileID() != null
    }

    fun isPureWhiteboard(): Boolean {
        return getFileID() == null
    }

    fun getFileID(): String? {
        return when {
            zegoDocsView != null -> {
                zegoDocsView!!.getFileID()
            }
            whiteboardViewList.isNotEmpty() -> {
                val fileInfo = whiteboardViewList.first().getWhiteboardViewModel().fileInfo
                if (fileInfo.fileID.isEmpty()) return null else fileInfo.fileID
            }
            else -> {
                null
            }
        }
    }

    fun getExcelSheetNameList(): MutableList<String> {
        return if (isExcel() && isDocsViewLoadSuccessed()) {
            zegoDocsView!!.getSheetNameList()
        } else {
            mutableListOf()
        }
    }

    fun selectExcelSheet(sheetIndex: Int, selectResult: (Int, String) -> Unit) {
        if (sheetIndex < 0 || sheetIndex > getExcelSheetNameList().size - 1) {
            return
        }
        if (isExcel() && isDocsViewLoadSuccessed()) {
            val firstOrNull = whiteboardViewList.firstOrNull {
                it.getWhiteboardViewModel().fileInfo.fileName == getExcelSheetNameList()[sheetIndex]
            }
            firstOrNull?.let {
                val model = it.getWhiteboardViewModel()
                Log.i(TAG, "selectSheet,fileName：${model.fileInfo.fileName}，${model.whiteboardID}")
                val roomService = ZegoSDKManager.getInstance().roomService
                roomService.sendCurrentWhiteboardID(model.whiteboardID) { errorCode ->
                    if (errorCode == 0) {
                        currentWhiteboardID = roomService.currentWhiteboardID
                    }
                    selectResult.invoke(errorCode, model.fileInfo.fileName)
                }
            }
        }
    }

    fun isExcel(): Boolean {
        return getFileType() == ZegoDocsViewConstants.ZegoDocsViewFileTypeELS
    }

    fun isDynamicPPT(): Boolean {
        return getFileType() == ZegoDocsViewConstants.ZegoDocsViewFileTypeDynamicPPTH5
    }

    fun isPPT(): Boolean {
        return getFileType() == ZegoDocsViewConstants.ZegoDocsViewFileTypePPT
    }

    fun hasThumbUrl(): Boolean {
        var docType = getFileType()
        return docType == ZegoDocsViewConstants.ZegoDocsViewFileTypeDynamicPPTH5 ||
                docType == ZegoDocsViewConstants.ZegoDocsViewFileTypePPT ||
                docType == ZegoDocsViewConstants.ZegoDocsViewFileTypePDF ||
                docType == ZegoDocsViewConstants.ZegoDocsViewFileTypePDFAndImages
    }

    fun getThumbnailUrlList(): ArrayList<String> {
        var urls = ArrayList<String>()
        if (hasThumbUrl() && zegoDocsView != null) {
            return zegoDocsView!!.getThumbnailUrlList()
        }
        return urls
    }


    fun getFileType(): Int {
        return when {
            zegoDocsView != null && isDocsViewLoadSuccessed() -> {
                zegoDocsView!!.getFileType()
            }
            whiteboardViewList.isNotEmpty() -> {
                // 任意一个白板，包含的是同样的 fileInfo
                whiteboardViewList.first().getWhiteboardViewModel().fileInfo.fileType
            }
            else -> {
                ZegoDocsViewConstants.ZegoDocsViewFileTypeUnknown
            }
        }
    }

    fun supportDragWhiteboard(): Boolean {
        return !(isPureWhiteboard() || isDynamicPPT() || isPPT())
    }

    fun getCurrentWhiteboardName(): String? {
        return getCurrentWhiteboardModel().name
    }

    fun getCurrentWhiteboardModel(): ZegoWhiteboardViewModel {
        return currentWhiteboardView!!.getWhiteboardViewModel()
    }

    fun getCurrentWhiteboardMsg(): String {
        return "modelMessage:name:${getCurrentWhiteboardModel().name},whiteboardID:${getCurrentWhiteboardModel().whiteboardID}," +
                "fileInfo:${getCurrentWhiteboardModel().fileInfo.fileName}" +
                "hori:${getCurrentWhiteboardModel().horizontalScrollPercent},vertical:${getCurrentWhiteboardModel().verticalScrollPercent}"
    }

    fun addTextEdit() {
        currentWhiteboardView?.addTextEdit(context)
    }

    fun undo() {
        currentWhiteboardView?.undo()
    }

    fun redo() {
        currentWhiteboardView?.redo()
    }

    fun clearCurrentPage() {
        val curPageRectF = if (isPureWhiteboard()) {
            currentWhiteboardView?.let {
                val width = it.width.toFloat()
                val height = it.height.toFloat()
                val pageOffsetX = width * (getCurrentPage() - 1)
                val pageOffsetY = 0F

                RectF(
                    pageOffsetX,
                    pageOffsetY,
                    (pageOffsetX + width),
                    (pageOffsetY + height)
                )
            }

        } else {
            zegoDocsView!!.currentPageInfo!!.rect
        }

        Log.i(TAG, "clearCurrentPage: ${curPageRectF.toString()}")
        currentWhiteboardView?.clear(curPageRectF)
    }

    fun saveImage() {
        PermissionHelper.onWriteSDCardPermissionGranted(context as Activity) { grant ->
            if (grant) {
                currentWhiteboardView?.whiteboardViewModel?.let {
//                    ToastUtils.showCenterToast(context.getString(R.string.start_save_image))
                    Log.i(TAG, "saveImage() start")
                    var name = it.name
                    // 去掉后缀名
                    if (name.contains(".")) {
                        name = name.substring(0, name.lastIndexOf("."))
                    }
                    FileUtil.saveImage(
                        name, this
                    ) { success ->
                        if (success) {
                            ToastUtils.showCenterToast(R.string.save_succeed)
                        } else {
                            ToastUtils.showCenterToast(R.string.save_failed)
                        }
                    }

                }
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        context as Activity,
                        Permission.READ_EXTERNAL_STORAGE
                    )
                ) {
                    ZegoDialog.Builder(context as Activity)
                        .setTitle(context.getString(R.string.disable_save_image_title))
                        .setMessage(context.getString(R.string.jump_settings_for_permission))
                        .setPositiveButton(context.getString(R.string.jump_to_settings)) { dialog, _ ->
                            dialog.dismiss()
                            val intent =
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri: Uri = Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)

                        }
                        .setNegativeButton(R.string.cancel) { dialog, _ ->
                            dialog.dismiss()

                        }
                        .setNegativeButtonBackground(R.drawable.drawable_dialog_confirm2)
                        .setNegativeButtonTextColor(R.color.colorAccent)
                        .setButtonWidth(80)
                        .setMaxDialogWidth(320)
                        .create().showWithLengthLimit()

                }
            }
        }

    }

    fun setCanDraw(canDraw: Boolean) {
        if (canDraw) {
            if (ClassRoomManager.me().sharable) {
                currentWhiteboardView?.setWhiteboardOperationMode(
                    ZegoWhiteboardConstants.ZegoWhiteboardOperationModeDraw
                            or ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom
                            or ZegoWhiteboardConstants.ZegoWhiteboardOperationModeScroll
                )
            } else {
                currentWhiteboardView?.setWhiteboardOperationMode(
                    ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom
                )
            }
        } else {
            if (ClassRoomManager.me().sharable) {
                currentWhiteboardView?.setWhiteboardOperationMode(
                            ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom
                            or ZegoWhiteboardConstants.ZegoWhiteboardOperationModeScroll
                )
            } else {
                currentWhiteboardView?.setWhiteboardOperationMode(
                    ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom
                )
            }
        }
    }

    fun setUserOperationMode(mode: Int) {
        currentWhiteboardView?.setWhiteboardOperationMode(mode)
    }

    fun scrollTo(horizontalPercent: Float, verticalPercent: Float, currentStep: Int = 1) {
        Log.d(
            TAG,
            "scrollTo() called with: horizontalPercent = $horizontalPercent, verticalPercent = $verticalPercent, currentStep = $currentStep"
        )
        currentWhiteboardView?.scrollTo(horizontalPercent, verticalPercent, currentStep)
    }

    fun clear() {
        currentWhiteboardView?.clear()
    }

    private fun addDocsView(docsView: ZegoDocsView, estimatedSize: Size) {
        Log.d(TAG, "addDocsView, estimatedSize:$estimatedSize")
        docsView.setEstimatedSize(estimatedSize.width, estimatedSize.height)
        this.zegoDocsView = docsView
        addView(
            zegoDocsView, 0, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun addWhiteboardView(zegoWhiteboardView: ZegoWhiteboardView) {
        val model = zegoWhiteboardView.whiteboardViewModel
        Log.i(
            TAG, "addWhiteboardView:${model.whiteboardID},${model.name},${model.fileInfo.fileName}"
        )
        this.whiteboardViewList.add(zegoWhiteboardView)

        addView(
            zegoWhiteboardView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun connectDocsViewAndWhiteboardView(zegoWhiteboardView: ZegoWhiteboardView) {
        Log.i(TAG, "connectDocsViewAndWhiteboardView...")
        zegoDocsView?.let { docsview ->
            if (docsview.getVisibleSize().height != 0 || docsview.getVisibleSize().width != 0) {
                zegoWhiteboardView.setVisibleRegion(zegoDocsView!!.getVisibleSize())
            }
            zegoWhiteboardView.setScrollListener { horizontalPercent, verticalPercent ->
                Log.d(
                    TAG,
                    "ScrollListener.onScroll,horizontalPercent:${horizontalPercent},verticalPercent:${verticalPercent}"
                )
                if (isDynamicPPT()) {
                    val page = calcDynamicPPTPage(verticalPercent)
                    val model = zegoWhiteboardView.whiteboardViewModel
                    val stepChanged = docsview.currentStep != model.pptStep
                    val pageChanged = docsview.currentPage != page
                    Log.i(
                        TAG,
                        "page:${page},step:${model.pptStep},stepChanged:$stepChanged,pageChanged:$pageChanged"
                    )
                    docsview.flipPage(page, model.pptStep) { result ->
                        Log.i(TAG, "docsview.flipPage() : result = $result")
                    }
                    internalScrollListener.onScroll(horizontalPercent, verticalPercent)
                } else {
                    docsview.scrollTo(verticalPercent) { complete ->
                        internalScrollListener.onScroll(0f, verticalPercent)
                    }
                }

            }

            if (isDynamicPPT()) {
                // 这些需要在第一页加载出来才能
                // 对于动态PPT，H5可以自行播放动画，需要同步给白板，再同步给其他端的用户
                docsview.setAnimationListener(IZegoDocsViewAnimationListener {
                    if (windowVisibility == View.VISIBLE) {
                        zegoWhiteboardView.playAnimation(it)
                    }
                })

                docsview.setStepChangeListener(object : IZegoDocsViewCurrentStepChangeListener {
                    override fun onChanged() {
                    }

                    override fun onStepChangeForClick() {
                        // 动态PPT，直接点击H5，触发翻页、步数变化
                        Log.d(TAG, "onStepChangeForClick() called")
                        scrollTo(0f, docsview.verticalPercent, docsview.currentStep)
                    }
                })
            }
            // 对于动态PPT，其他端有播放动画，需要同步给docsView进行播放动画
            zegoWhiteboardView.setAnimationListener { animation ->
                Log.d(TAG, "setAnimationListener() called")
                docsview.playAnimation(animation)
            }
            zegoWhiteboardView.setScaleListener(IZegoWhiteboardViewScaleListener { scaleFactor, transX, transY ->
//            Log.d(TAG,"scaleFactor:$scaleFactor,transX:$transX,transY:$transY")
                docsview.scaleDocsView(scaleFactor, transX, transY)
            })
        }

        post {
            val model = zegoWhiteboardView.whiteboardViewModel
            val horPercent = model.horizontalScrollPercent
            val verPercent = model.verticalScrollPercent
            val currentStep = model.pptStep
            Log.d(TAG, "horPercent:$horPercent,verPercent:$verPercent,currentStep:$currentStep")
            if (isDynamicPPT()) {
                // 此处是首次加载，要跳转到到文件对应页。完成后需要判断是否播动画
                zegoDocsView?.let {
                    val targetPage = calcDynamicPPTPage(verPercent)
                    it.flipPage(targetPage, currentStep) { result ->
                        if (result) {
                            zegoWhiteboardView.whiteboardViewModel.h5Extra?.let { h5Extra ->
                                it.playAnimation(h5Extra)
                            }
                        }
                    }
                }
            } else {
                zegoDocsView?.scrollTo(verPercent, null)
            }
            // 假如白板已经滚过了，这时候额外通知外面更新当前状态（页码等）
            internalScrollListener.onScroll(horPercent, verPercent)
        }
    }

    fun calcDynamicPPTPage(verticalPercent: Float): Int {
        return if (isDynamicPPT()) {
            if (isDocsViewLoadSuccessed()) {
                val page = round(verticalPercent * zegoDocsView!!.pageCount).toInt() + 1
                page
            } else {
                1
            }
        } else {
            throw IllegalArgumentException("only used for dynamic PPT")
        }
    }

    private fun onPureWhiteboardViewAdded(zegoWhiteboardView: ZegoWhiteboardView) {
        val model = zegoWhiteboardView.getWhiteboardViewModel()
        currentWhiteboardID = model.whiteboardID
        zegoWhiteboardView.setScrollListener(IZegoWhiteboardViewScrollListener { horizontalPercent, verticalPercent ->
            internalScrollListener.onScroll(horizontalPercent, verticalPercent)
        })
    }

    /**
     * 添加纯白板
     */
    fun onReceivePureWhiteboardView(zegoWhiteboardView: ZegoWhiteboardView) {
        zegoWhiteboardView.setBackgroundColor(Color.parseColor("#f4f5f8"))
        addWhiteboardView(zegoWhiteboardView)
        onPureWhiteboardViewAdded(zegoWhiteboardView)
        whiteboardViewAddFinished = true
    }

    /**
     * 创建纯白板，aspectWidth，aspectHeight:宽高比
     */
    fun createPureWhiteboardView(
        aspectWidth: Int, aspectHeight: Int, pageCount: Int,
        whiteboardName: String, requestResult: (Int) -> Unit
    ) {
        val data = ZegoWhiteboardViewModel()
        data.aspectHeight = aspectHeight
        data.aspectWidth = aspectWidth
        data.name = whiteboardName
        data.pageCount = pageCount
        data.roomId = CONFERENCE_ID
        ZegoWhiteboardManager.getInstance().createWhiteboardView(data)
        { errorCode, zegoWhiteboardView ->
            Log.d(
                TAG,
                "createPureWhiteboardView,name:${data.name},errorCode:${errorCode}"
            )
            if (errorCode == 0 && zegoWhiteboardView != null) {
                onReceivePureWhiteboardView(zegoWhiteboardView)
            } else {
                Toast.makeText(context, "创建白板失败，错误码:$errorCode", Toast.LENGTH_LONG).show()
            }
            requestResult.invoke(errorCode)
        }
    }

    fun destroyWhiteboardView(requestResult: (Int) -> Unit) {
        if (isExcel()) {
            var count = whiteboardViewList.size
            var success = true
            var code = 0
            whiteboardViewList.forEach {
                val whiteboardID = it.getWhiteboardViewModel().whiteboardID
                ZegoWhiteboardManager.getInstance().destroyWhiteboardView(whiteboardID)
                { errorCode, _ ->
                    //因为所有的回调都是在主线程，所以不用考虑多线程
                    count--
                    if (errorCode != 0) {
                        success = false
                        code = errorCode
                    }
                    if (count == 0) {
                        if (!success) {
                            Toast.makeText(context, "删除白板失败:错误码:$code", Toast.LENGTH_LONG)
                                .show()
                        } else {
                            zegoDocsView?.unloadFile()
                            fileLoadSuccessed = false
                        }
                        requestResult.invoke(errorCode)
                    }
                }
            }
        } else {
            ZegoWhiteboardManager.getInstance().destroyWhiteboardView(currentWhiteboardID)
            { errorCode, _ ->
                if (errorCode != 0) {
                    Toast.makeText(context, "删除白板失败:错误码:$errorCode", Toast.LENGTH_LONG).show()
                } else {
                    zegoDocsView?.unloadFile()
                    fileLoadSuccessed = false
                }
                requestResult.invoke(errorCode)
            }
        }
    }

    /**
     * 收到文件白板
     */
    fun onReceiveFileWhiteboard(
        estimatedSize: Size,
        zegoWhiteboardView: ZegoWhiteboardView,
        processResult: (Int, ZegoWhiteboardViewHolder) -> Unit
    ) {
        val fileInfo = zegoWhiteboardView.getWhiteboardViewModel().fileInfo
        Log.d(
            TAG,
            "onReceiveFileWhiteboard() called with: estimatedSize = $estimatedSize, zegoWhiteboardView = ${fileInfo.fileName}"
        )
        addWhiteboardView(zegoWhiteboardView)
        if (zegoDocsView != null) {
            zegoWhiteboardView.visibility = View.GONE
            processResult(0, this)
        } else {
            val fileID = fileInfo.fileID
            visibility = View.GONE
            currentWhiteboardID = zegoWhiteboardView.getWhiteboardViewModel().whiteboardID
            loadFileWhiteBoardView(fileID, estimatedSize) { errorCode: Int, _: ZegoDocsView ->
                if (errorCode == 0) {
                    if (isExcel()) { // excel要等到load完才设置，因为要switchSheet
                        currentWhiteboardID =
                            zegoWhiteboardView.getWhiteboardViewModel().whiteboardID
                    } else {
                        connectDocsViewAndWhiteboardView(zegoWhiteboardView)
                    }
                    processResult.invoke(errorCode, this)
                } else {
                    Toast.makeText(context, "加载文件失败，错误代码 $errorCode", Toast.LENGTH_LONG).show()
                    processResult.invoke(errorCode, this)
                    // 可以考虑给个界面按钮点击重试
                }
            }
        }
    }

    /**
     * 加载白板view
     */
    private fun loadFileWhiteBoardView(
        fileID: String,
        estimatedSize: Size,
        requestResult: (Int, ZegoDocsView) -> Unit
    ) {
        Log.i(
            TAG,
            "loadFileWhiteBoardView,start loadFile fileID:${fileID},estimatedSize:${estimatedSize}"
        )
        ZegoDocsView(context).let {
            addDocsView(it, estimatedSize)
            it.loadFile(fileID, "", IZegoDocsViewLoadListener { errorCode ->
                fileLoadSuccessed = errorCode == 0
                if (errorCode == 0) {
                    Log.i(
                        TAG,
                        "loadFileWhiteBoardView loadFile fileID:${fileID} success,getVisibleSize:${it.getVisibleSize()}," +
                                "contentSize:${it.getContentSize()}," + "name:${it.getFileName()}" +
                                "nameList:${it.getSheetNameList()}"
                    )
                } else {
                    Log.i(
                        TAG,
                        "loadFileWhiteBoardView loadFile fileID:${fileID} failed，errorCode：${errorCode}"
                    )
                }
                requestResult.invoke(errorCode, it)
            })
        }
    }

    fun createDocsAndWhiteBoardView(
        fileID: String, estimatedSize: Size, createResult: (Int) -> Unit
    ) {
        loadFileWhiteBoardView(fileID, estimatedSize)
        { errorCode, docsView ->
            if (errorCode == 0) {
                if (isExcel()) {
                    createExcelWhiteboardViewList(docsView, createResult)
                } else {
                    createWhiteBoardViewInner(docsView, 0, createResult)
                }
            } else {
                createResult(errorCode)
                Toast.makeText(context, "加载文件失败，错误代码 $errorCode", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun isDocsViewLoadSuccessed(): Boolean {
        return fileLoadSuccessed
    }

    fun isWhiteboardViewAddFinished(): Boolean {
        return whiteboardViewAddFinished
    }

    private fun createExcelWhiteboardViewList(
        docsView: ZegoDocsView,
        requestResult: (Int) -> Unit
    ) {
        val sheetCount = getExcelSheetNameList().size
        var processCount = 0
        var resultCode = 0
        for (index in 0 until sheetCount) {
            createWhiteBoardViewInner(docsView, index) { code ->
                if (code != 0) {
                    resultCode = code
                }
                processCount++
                if (processCount == sheetCount) {
                    selectExcelSheet(0) { errorCode, _ ->
                        whiteboardViewAddFinished = errorCode == 0
                        if (errorCode == 0) {
                            requestResult.invoke(resultCode)
                        } else {
                            requestResult.invoke(errorCode)
                        }
                    }
                }
            }
        }
    }

    private fun createWhiteBoardViewInner(
        docsView: ZegoDocsView, index: Int,
        requestResult: (Int) -> Unit
    ) {
        val data = ZegoWhiteboardViewModel()
        data.aspectWidth = docsView.getContentSize().width
        data.aspectHeight = docsView.getContentSize().height
        data.name = docsView.getFileName()!!
        data.fileInfo.fileID = docsView.getFileID()!!
        data.fileInfo.fileType = docsView.getFileType()
        if (isExcel()) {
            data.fileInfo.fileName = docsView.getSheetNameList()[index]
        }
        data.roomId = CONFERENCE_ID

        ZegoWhiteboardManager.getInstance().createWhiteboardView(data)
        { errorCode, zegoWhiteboardView ->
            Log.d(
                TAG,
                "createWhiteboardView,name:${data.name},fileName:${data.fileInfo.fileName}"
            )
            if (errorCode == 0 && zegoWhiteboardView != null) {
                addWhiteboardView(zegoWhiteboardView)
                if (!isExcel()) {
                    currentWhiteboardID =
                        zegoWhiteboardView.getWhiteboardViewModel().whiteboardID
                    connectDocsViewAndWhiteboardView(zegoWhiteboardView)
                    whiteboardViewAddFinished = errorCode == 0
                }
            } else {
                Toast.makeText(
                    context,
                    "创建白板失败，错误码:$errorCode",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestResult.invoke(errorCode)
        }
    }

    fun flipToPage(targetPage: Int) {
        Log.i(TAG, "targetPage:${targetPage}")
        if (zegoDocsView != null && getFileID() != null && isDocsViewLoadSuccessed()) {
            zegoDocsView!!.flipPage(targetPage) { result ->
                Log.i(TAG, "it.flipToPage() result:$result")
                if (result) {
                    scrollTo(0f, zegoDocsView!!.getVerticalPercent())
                }
            }
        } else {
            scrollTo((targetPage - 1).toFloat() / getPageCount(), 0f)
        }
    }

    /**
     * 此处的page是从1开始的
     */
    fun flipToPrevPage(): Int {
        val currentPage = getCurrentPage()
        val targetPage = if (currentPage - 1 <= 0) 1 else currentPage - 1
        if (targetPage != currentPage) {
            flipToPage(targetPage)
        }
        return targetPage
    }

    fun flipToNextPage(): Int {
        val currentPage = getCurrentPage()
        val targetPage =
            if (currentPage + 1 > getPageCount()) getPageCount() else currentPage + 1
        if (targetPage != currentPage) {
            flipToPage(targetPage)
        }
        return targetPage
    }

    fun previousStep() {
        Log.d(TAG, "previousStep() called,fileLoadSuccessed:${isDocsViewLoadSuccessed()}")
        if (getFileID() != null && isDynamicPPT() && isDocsViewLoadSuccessed()) {
            zegoDocsView?.let {
                it.previousStep(IZegoDocsViewScrollCompleteListener { result ->
                    Log.d(TAG, "previousStep:result = $result")
                    if (result) {
                        scrollTo(0f, it.getVerticalPercent(), it.getCurrentStep())
                    }
                })
            }
        }
    }

    fun nextStep() {
        Log.i(TAG, "nextStep() called,fileLoadSuccessed:${isDocsViewLoadSuccessed()}")
        if (getFileID() != null && isDynamicPPT() && isDocsViewLoadSuccessed()) {
            zegoDocsView?.let {
                it.nextStep(IZegoDocsViewScrollCompleteListener { result ->
                    Log.i(TAG, "nextStep:result = $result")
                    if (result) {
                        scrollTo(0f, it.getVerticalPercent(), it.getCurrentStep())
                    }
                })
            }
        }
    }

    fun getPageCount(): Int {
        return if (getFileID() != null) {
            zegoDocsView!!.getPageCount()
        } else {
            getCurrentWhiteboardModel().pageCount
        }
    }

    /**
     * 第二页滚动到一半，才认为是第二页
     */
    fun getCurrentPage(): Int {
        return if (getFileID() != null) {
            zegoDocsView!!.getCurrentPage()
        } else {
            val percent = currentWhiteboardView!!.getHorizontalPercent()
            val currentPage = round(percent * getPageCount()).toInt() + 1
            Log.i(TAG, "getCurrentPage,percent:${percent},currentPage:${currentPage}")
            return if (currentPage < getPageCount()) currentPage else getPageCount()
        }
    }

    fun setWhiteboardScrollChangeListener(listener: IZegoWhiteboardViewScrollListener) {
        outScrollListener = listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 保底处理
        zegoDocsView?.unloadFile()
        fileLoadSuccessed = false
    }

    fun addText(text: String?, positionX: Int, positionY: Int) {
        currentWhiteboardView?.addText(text, positionX, positionY)
    }

    fun enableUserOperation(enable: Boolean) {
        if (enable) {
            currentWhiteboardView?.setWhiteboardOperationMode(
                ZegoWhiteboardConstants.ZegoWhiteboardOperationModeDraw
                        or ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom
                        or ZegoWhiteboardConstants.ZegoWhiteboardOperationModeScroll
            )
        } else {
            currentWhiteboardView?.setWhiteboardOperationMode(ZegoWhiteboardConstants.ZegoWhiteboardOperationModeZoom)
        }
    }

    fun deleteSelectedGraphics() {
        currentWhiteboardView?.deleteSelectedGraphics()
    }

    /**
     * 停止当前正在播放的视频
     */
    fun stopPlayPPTVideo() {
        if (isDynamicPPT()) {
            zegoDocsView?.let {
                it.stopPlay(it.currentPage)
            }
        }
    }

    fun reload() {
        if (zegoDocsView != null) {
            zegoDocsView?.reloadFile(IZegoDocsViewLoadListener { loadCode ->
                fileLoadSuccessed = loadCode == 0
                if (loadCode == 0) {
                    Log.d(TAG, "visibleRegion:${zegoDocsView!!.getVisibleSize()}")
                    currentWhiteboardView?.setVisibleRegion(zegoDocsView!!.visibleSize)
                }
            })
        }
    }
}