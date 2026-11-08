/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.idea

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/** Navigation uses the same bundle's source coordinates, never JVM declarations. */
class GuestSourceNavigation : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val reference = element.getParentOfType<KtNameReferenceExpression>(strict = false) ?: return null
        val module = ModuleUtilCore.findModuleForPsiElement(reference)?.let(GuestSourceModule::forModule) ?: return null
        val symbolName =
            analyze(reference) {
                when (val symbol = reference.mainReference.resolveToSymbol()) {
                    is KaConstructorSymbol -> symbol.containingClassId?.asSingleFqName()?.asString()
                    is KaCallableSymbol -> symbol.callableId?.asSingleFqName()?.asString()
                    is KaClassLikeSymbol -> symbol.classId?.asSingleFqName()?.asString()
                    else -> null
                }
            } ?: return null
        val modules = listOf(module.bundle.builtins) + module.bundle.modules
        val declaration = modules.flatMap { it.declarations }.firstOrNull { it.symbol == symbolName } ?: return null
        val source = modules.flatMap { it.sources }.single { it.path == declaration.sourcePath }
        val virtualFile =
            module.navigationFiles.computeIfAbsent(source.path) {
                LightVirtualFile(
                    source.path.substringAfterLast('/'),
                    KotlinFileType.INSTANCE,
                    source.content.toByteArray().decodeToString(),
                ).also {
                    it.isWritable = false
                }
            }
        val psi = PsiManager.getInstance(element.project).findFile(virtualFile) ?: return null
        return arrayOf(psi.findElementAt(declaration.startUtf16) ?: psi)
    }
}
