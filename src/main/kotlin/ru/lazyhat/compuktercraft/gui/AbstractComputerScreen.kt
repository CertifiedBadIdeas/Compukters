// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.gui

import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.menu.AbstractComputerMenu
import java.util.Optional

/**
 * The base class of all screens with a computer terminal (i.e. [ComputerScreen]). This works with
 * [AbstractComputerMenu] to handle the common behaviour such as the terminal, input and file uploading.
 *
 * @param <T> The concrete type of the associated menu.
</T> */
abstract class AbstractComputerScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
    protected val sidebarYOffset: Int,
) : AbstractContainerScreen<T>(container, player, title) {
    protected var terminal: TerminalWidget? = null
    protected var terminalData: Terminal
    protected val family: ComputerFamily = container.family
    protected val input: InputHandler = ClientInputHandler(menu)

    private var uploadNagDeadline = Long.MAX_VALUE
    private val uploadMaxSize: Int = container.uploadMaxSize
    private val displayStack: ItemStack = container.displayStack

    init {
        CompukterCraftMod.LOGGER.info("ComputerID: AbstractComputerScreen init")
        terminalData = container.getTerminal()
    }

    protected abstract fun createTerminal(): TerminalWidget

    @JvmName("getTerminalPublic")
    protected fun getTerminal(): TerminalWidget {
        checkNotNull(terminal) { "Screen has not been initialised yet" }
        return terminal!!
    }

    override fun init() {
        CompukterCraftMod.LOGGER.info("AbstractComputerScreen init()")
        super.init()
        terminal = addRenderableWidget(createTerminal())
        ComputerSidebar.addButtons(menu::isOn, input, ::addRenderableWidget, leftPos, topPos + sidebarYOffset)
        focused = terminal
    }

    public override fun containerTick() {
        super.containerTick()
        getTerminal().update()

        if (uploadNagDeadline != Long.MAX_VALUE && Util.getNanos() >= uploadNagDeadline) {
            ItemToast(minecraft(), displayStack, NO_RESPONSE_TITLE, NO_RESPONSE_MSG, ItemToast.TRANSFER_NO_RESPONSE_TOKEN)
                .showOrReplace(minecraft().getToasts())
            uploadNagDeadline = Long.Companion.MAX_VALUE
        }
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        // Forward the tab key to the terminal, rather than moving between controls.
        if (key == GLFW.GLFW_KEY_TAB && focused != null && focused === terminal) {
            return focused!!.keyPressed(key, scancode, modifiers)
        }

        return super.keyPressed(key, scancode, modifiers)
    }

    override fun mouseReleased(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        // Reimplement ContainerEventHandler.mouseReleased, as it's not called in vanilla (it is in Forge, but that
        // shouldn't matter).
        isDragging = false
        val child: Optional<GuiEventListener?> = getChildAt(x, y)
        if (child.isPresent && child.get().mouseReleased(x, y, button)) return true

        return super.mouseReleased(x, y, button)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTicks)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val changed: Boolean = super.mouseClicked(x, y, button)
        // Clicking the terminate/shutdown button steals focus, which means then pressing "enter" will click the button
        // again. Restore the focus to the terminal in these cases.
        if (getFocused() is DynamicImageButton) setFocused(terminal)
        return changed
    }

    override fun mouseDragged(
        x: Double,
        y: Double,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean =
        (focused != null && focused!!.mouseDragged(x, y, button, deltaX, deltaY)) ||
            super.mouseDragged(x, y, button, deltaX, deltaY)

    override fun setFocused(listener: GuiEventListener?) {
        // Don't clear and re-focus if we're already focused.
        if (listener !== focused) super.setFocused(listener)
    }

    override fun renderLabels(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        // Skip rendering labels.
    }

//    override fun onFilesDrop(files: MutableList<Path>) {
//        if (files.isEmpty()) return
//
//        if (!menu.isOn()) {
//            alert(UploadResult.FAILED_TITLE, UploadResult.COMPUTER_OFF_MSG)
//            return
//        }
//
//        var size: Long = 0
//
//        val toUpload: MutableList<FileUpload?> = ArrayList<FileUpload?>()
//        for (file in files) {
//            // TODO: Recurse directories? If so, we probably want to shunt this off-thread.
//            if (!Files.isRegularFile(file)) continue
//
//            try {
//                Files.newByteChannel(file).use { sbc ->
//                    val fileSize = sbc.size()
//                    if (fileSize > uploadMaxSize || (
//                            fileSize.let {
//                                size += it
//                                size
//                            }
//                        ) >= uploadMaxSize
//                    ) {
//                        alert(UploadResult.FAILED_TITLE, UploadResult.TOO_MUCH_MSG)
//                        return
//                    }
//
//                    val name = file.getFileName().toString()
//                    if (name.length > UploadFileMessage.MAX_FILE_NAME) {
//                        alert(UploadResult.FAILED_TITLE, Component.translatable("gui.computercraft.upload.failed.name_too_long"))
//                        return
//                    }
//
//                    val buffer = ByteBuffer.allocateDirect(fileSize.toInt())
//                    sbc.read(buffer)
//                    buffer.flip()
//
//                    val digest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
//                        FileUpload.getDigest(
//                            buffer,
//                        )
//                    if (digest == null) {
//                        alert(UploadResult.FAILED_TITLE, Component.translatable("gui.computercraft.upload.failed.corrupted"))
//                        return
//                    }
//                    toUpload.add(FileUpload(name, buffer, digest))
//                }
//            } catch (e: IOException) {
//                LOG.error("Failed uploading files", e)
//                alert(
//                    UploadResult.FAILED_TITLE,
//                    Component.translatable("gui.computercraft.upload.failed.generic", "Cannot compute checksum"),
//                )
//            }
//        }
//
//        if (toUpload.size > UploadFileMessage.MAX_FILES) {
//            alert(UploadResult.FAILED_TITLE, Component.translatable("gui.computercraft.upload.failed.too_many_files"))
//            return
//        }
//
//        if (!toUpload.isEmpty()) UploadFileMessage.send(menu, toUpload, ClientNetworking::sendToServer)
//    }

//    fun uploadResult(
//        result: UploadResult,
//        @Nullable
//        message: Component?,
//    ) {
//        when (result) {
//            QUEUED -> {
//                if (Config.uploadNagDelay > 0) {
//                    uploadNagDeadline = Util.getNanos() + TimeUnit.SECONDS.toNanos(Config.uploadNagDelay)
//                }
//            }
//
//            CONSUMED -> {
//                uploadNagDeadline = Long.Companion.MAX_VALUE
//            }
//
//            ERROR -> {
//                alert(UploadResult.FAILED_TITLE, assertNonNull(message))
//            }
//        }
//    }

    private fun alert(
        title: Component,
        message: Component,
    ) {
        OptionScreen.show(
            minecraft(),
            title,
            message,
            mutableListOf(OptionScreen.newButton(OK, { b -> minecraft().setScreen(this) })),
            { minecraft().setScreen(this) },
        )
    }

    private fun minecraft(): Minecraft = checkNotNull(minecraft)

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(AbstractComputerScreen::class.java)

        private val OK: Component = Component.translatable("gui.ok")
        private val NO_RESPONSE_TITLE: Component = Component.translatable("gui.compuktercraft.upload.no_response")
        private val NO_RESPONSE_MSG: Component =
            Component.translatable(
                "gui.compuktercraft.upload.no_response.msg",
                Component.literal("import").withStyle(ChatFormatting.DARK_GRAY),
            )
    }
}
