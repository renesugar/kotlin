/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.lower.MultifileFacadeFileEntry
import org.jetbrains.kotlin.backend.jvm.lower.buildAssertionsDisabledField
import org.jetbrains.kotlin.backend.jvm.lower.hasAssertionsDisabledField
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.inline.*
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.annotations.TRANSIENT_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.annotations.VOLATILE_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.jvm.checkers.JvmSimpleNameBacktickChecker
import org.jetbrains.kotlin.resolve.jvm.diagnostics.*
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmClassSignature
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.io.File

abstract class ClassCodegen protected constructor(
    val irClass: IrClass,
    val context: JvmBackendContext,
    val parentFunction: IrFunction?,
) {
    private val parentClassCodegen = (parentFunction?.parentAsClass ?: irClass.parent as? IrClass)?.let { context.getClassCodegen(it) }

    protected val state get() = context.state
    protected val typeMapper get() = context.typeMapper

    val type: Type = typeMapper.mapClass(irClass)

    val reifiedTypeParametersUsages = ReifiedTypeParametersUsages()

    val innerClasses = mutableSetOf<IrClass>()
    private val regeneratedObjectNameGenerators = mutableMapOf<String, NameGenerator>()
    private val generatedInlineMethods = mutableMapOf<IrFunction, SMAPAndMethodNode>()
    private var generatingClInit = false
    private var generated = false

    fun getRegeneratedObjectNameGenerator(function: IrFunction): NameGenerator {
        val name = if (function.name.isSpecial) "special" else function.name.asString()
        return regeneratedObjectNameGenerators.getOrPut(name) {
            NameGenerator("${type.internalName}\$$name\$\$inlined")
        }
    }

    fun generate() {
        assert(parentFunction != null || parentClassCodegen == null) {
            "nested class ${irClass.render()} should only be generated by a call to generate() on its parent class"
        }
        // TODO do not generate `finally` blocks more than once -- this is what causes local objects
        //      (and inline lambdas) to be generated multiple times.
        if (generated) return
        generated = true
        begin(null).generate()
    }

    fun generateAssertFieldIfNeeded(): IrExpression? {
        if (irClass.hasAssertionsDisabledField(context))
            return null
        val topLevelClass = generateSequence(this) { it.parentClassCodegen }.last().irClass
        val field = irClass.buildAssertionsDisabledField(context, topLevelClass)
        irClass.declarations.add(field)
        // Normally, `InitializersLowering` would move the initializer to <clinit>, but
        // it's obviously too late for that.
        val init = IrSetFieldImpl(
            field.startOffset, field.endOffset, field.symbol, null,
            field.initializer!!.expression, context.irBuiltIns.unitType
        )
        if (generatingClInit) {
            // We're generating `<clinit>` right now. Attempting to do `body.statements.add` will cause
            // a concurrent modification error, so the currently active `ExpressionCodegen` needs to be
            // asked to generate this initializer directly.
            return init
        }
        val clinit = irClass.functions.singleOrNull { it.name.asString() == "<clinit>" }
        if (clinit != null) {
            (clinit.body as IrBlockBody).statements.add(0, init)
        } else {
            irClass.addFunction {
                name = Name.special("<clinit>")
                returnType = context.irBuiltIns.unitType
            }.apply {
                body = IrBlockBodyImpl(startOffset, endOffset, listOf(init))
            }
        }
        return null
    }

    fun generateMethodNode(method: IrFunction): SMAPAndMethodNode {
        if (!method.isInline && !method.alwaysNeedsContinuation()) {
            // Inline methods can be used multiple times by `IrSourceCompilerForInline`, suspend methods
            // could be used twice if they capture crossinline lambdas, and everything else is only
            // generated by `generateMethod` below so does not need caching.
            return FunctionCodegen(method, this).generate()
        }
        val (node, smap) = generatedInlineMethods.getOrPut(method) { FunctionCodegen(method, this).generate() }
        val copy = with(node) { MethodNode(Opcodes.API_VERSION, access, name, desc, signature, exceptions.toTypedArray()) }
        node.instructions.resetLabels()
        node.accept(copy)
        return SMAPAndMethodNode(copy, smap)
    }

    protected abstract fun begin(outerState: State?): State

    protected abstract inner class State {
        private val classOrigin = run {
            // The descriptor associated with an IrClass is never modified in lowerings, so it
            // doesn't reflect the state of the lowered class. To make the diagnostics work we
            // pass in a wrapped descriptor instead, except for lambdas where we use the descriptor
            // of the original function.
            // TODO: Migrate class builders away from descriptors
            val descriptor = WrappedClassDescriptor().apply { bind(irClass) }
            val psiElement = context.psiSourceManager.findPsiElement(irClass)
            when (irClass.origin) {
                IrDeclarationOrigin.FILE_CLASS ->
                    JvmDeclarationOrigin(JvmDeclarationOriginKind.PACKAGE_PART, psiElement, descriptor)
                JvmLoweredDeclarationOrigin.LAMBDA_IMPL, JvmLoweredDeclarationOrigin.FUNCTION_REFERENCE_IMPL ->
                    OtherOrigin(psiElement, irClass.attributeOwnerId.safeAs<IrFunctionReference>()?.symbol?.descriptor ?: descriptor)
                else ->
                    OtherOrigin(psiElement, descriptor)
            }
        }

        protected val visitor = state.factory.newVisitor(classOrigin, type, irClass.fileParent.loadSourceFilesInfo(context)).apply {
            val signature = getSignature(irClass, type, irClass.getSuperClassInfo(typeMapper), typeMapper)
            // Ensure that the backend only produces class names that would be valid in the frontend for JVM.
            if (context.state.classBuilderMode.generateBodies && signature.hasInvalidName()) {
                throw IllegalStateException("Generating class with invalid name '${type.className}': ${irClass.dump()}")
            }
            defineClass(
                irClass.psiElement,
                state.classFileVersion,
                irClass.flags,
                signature.name,
                signature.javaGenericSignature,
                signature.superclassName,
                signature.interfaces.toTypedArray()
            )
        }

        private val jvmSignatureClashDetector = JvmSignatureClashDetector(irClass, type, context)
        private val smap = context.getSourceMapper(irClass)

        fun generate() {
            // Generating a method node may cause the addition of a field with an initializer if an inline function
            // uses `assert` and the JVM assertion mode is enabled. To avoid concurrent modification errors,
            // there is a very specific member generation order (the JVM doesn't care).
            // 1. Any method other than `<clinit>` can add a field and a `<clinit>` statement:
            for (method in irClass.declarations.filterIsInstance<IrFunction>()) {
                if (method.name.asString() != "<clinit>") {
                    generateMethod(method)
                }
            }
            // 2. `<clinit>` can add a field (the statement is generated inline via the `return init` hack;
            //    see `generateAssertFieldIfNeeded`):
            val clinit = irClass.functions.singleOrNull { it.name.asString() == "<clinit>" }
            if (clinit != null) {
                generatingClInit = true
                generateMethod(clinit)
            }
            // 3. Now we have all the fields:
            for (declaration in irClass.fields) {
                generateField(declaration)
            }
            // 4. Generate inner classes at the end, to ensure that when the companion's metadata is serialized
            //    everything moved to the outer class has already been recorded in `globalSerializationBindings`.
            for (declaration in irClass.declarations) {
                if (declaration is IrClass) {
                    context.getClassCodegen(declaration).begin(this).generate()
                }
            }

            object : AnnotationCodegen(this@ClassCodegen, context) {
                override fun visitAnnotation(descr: String?, visible: Boolean): AnnotationVisitor {
                    return visitor.visitor.visitAnnotation(descr, visible)
                }
            }.genAnnotations(irClass, null, null)
            generateKotlinMetadataAnnotation()

            generateInnerAndOuterClasses()

            if (!smap.isTrivial || generateSequence(this@ClassCodegen) { it.parentClassCodegen }.any { it.parentFunction?.isInline == true }) {
                visitor.visitSMAP(smap, !context.state.languageVersionSettings.supportsFeature(LanguageFeature.CorrectSourceMappingSyntax))
            } else {
                visitor.visitSource(smap.sourceInfo!!.source, null)
            }

            visitor.done()
            jvmSignatureClashDetector.reportErrors(classOrigin)
        }

        private fun generateField(field: IrField) {
            if (field.isFakeOverride) return

            val fieldType = typeMapper.mapType(field)
            val fieldSignature =
                if (field.origin == IrDeclarationOrigin.PROPERTY_DELEGATE) null
                else context.methodSignatureMapper.mapFieldSignature(field)
            val fieldName = field.name.asString()
            val fv = visitor.newField(
                field.OtherOrigin, field.flags, fieldName, fieldType.descriptor,
                fieldSignature, (field.initializer?.expression as? IrConst<*>)?.value
            )

            jvmSignatureClashDetector.trackField(field, RawSignature(fieldName, fieldType.descriptor, MemberKind.FIELD))

            if (field.origin != JvmLoweredDeclarationOrigin.CONTINUATION_CLASS_RESULT_FIELD) {
                object : AnnotationCodegen(this@ClassCodegen, context) {
                    override fun visitAnnotation(descr: String?, visible: Boolean): AnnotationVisitor {
                        return fv.visitAnnotation(descr, visible)
                    }

                    override fun visitTypeAnnotation(descr: String?, path: TypePath?, visible: Boolean): AnnotationVisitor {
                        return fv.visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value, path, descr, visible)
                    }
                }.genAnnotations(field, fieldType, field.type)
            }

            bindFieldMetadata(field, fieldType, fieldName)
        }

        private fun generateMethod(method: IrFunction) {
            if (method.isFakeOverride) {
                jvmSignatureClashDetector.trackFakeOverrideMethod(method)
                return
            }

            val (node, methodSMAP) = generateMethodNode(method)
            node.preprocessSuspendMarkers(
                method.origin == JvmLoweredDeclarationOrigin.FOR_INLINE_STATE_MACHINE_TEMPLATE || method.isEffectivelyInlineOnly(),
                method.origin == JvmLoweredDeclarationOrigin.FOR_INLINE_STATE_MACHINE_TEMPLATE_CAPTURES_CROSSINLINE
            )
            val mv = with(node) { visitor.newMethod(method.OtherOrigin, access, name, desc, signature, exceptions.toTypedArray()) }
            val smapCopier = SourceMapCopier(smap, methodSMAP)
            val smapCopyingVisitor = object : MethodVisitor(Opcodes.API_VERSION, mv) {
                override fun visitLineNumber(line: Int, start: Label) =
                    super.visitLineNumber(smapCopier.mapLineNumber(line), start)
            }
            if (method.hasContinuation()) {
                // Generate a state machine within this method. The continuation class for it should be generated
                // lazily so that if tail call optimization kicks in, the unused class will not be written to the output.
                val continuationClass = method.continuationClass() // null for lambdas' invokeSuspend
                val continuationState = lazy { continuationClass?.let { context.getClassCodegen(it, method).begin(null) } ?: this }
                node.acceptWithStateMachine(method, this@ClassCodegen, smapCopyingVisitor) { continuationState.value.visitor }
                if (continuationClass != null && (continuationState.isInitialized() || method.alwaysNeedsContinuation())) {
                    continuationState.value.generate()
                }
            } else {
                node.accept(smapCopyingVisitor)
            }
            jvmSignatureClashDetector.trackMethod(method, RawSignature(node.name, node.desc, MemberKind.METHOD))
            bindMethodMetadata(method, Method(node.name, node.desc))
        }

        private fun generateInnerAndOuterClasses() {
            // JVMS7 (4.7.6): a nested class or interface member will have InnerClasses information
            // for each enclosing class and for each immediate member
            parentClassCodegen?.innerClasses?.add(irClass)
            for (codegen in generateSequence(this@ClassCodegen) { it.parentClassCodegen }.takeWhile { it.parentClassCodegen != null }) {
                innerClasses.add(codegen.irClass)
            }

            for (innerClass in innerClasses) {
                val outer =
                    if (context.customEnclosingFunction[innerClass.attributeOwnerId] != null) null
                    else innerClass.parent.safeAs<IrClass>()?.let(typeMapper::classInternalName)
                val inner = innerClass.name.takeUnless { it.isSpecial }?.asString()
                val flags = innerClass.calculateInnerClassAccessFlags(context)
                visitor.visitInnerClass(typeMapper.classInternalName(innerClass), outer, inner, flags)
            }

            // JVMS7 (4.7.7): A class must have an EnclosingMethod attribute if and only if
            // it is a local class or an anonymous class.
            //
            // The attribute contains the innermost class that encloses the declaration of
            // the current class. If the current class is immediately enclosed by a method
            // or constructor, the name and type of the function is recorded as well.
            if (parentClassCodegen != null) {
                val enclosingFunction = context.customEnclosingFunction[irClass.attributeOwnerId] ?: parentFunction
                if (enclosingFunction != null || irClass.isAnonymousObject) {
                    val method = enclosingFunction?.let(context.methodSignatureMapper::mapAsmMethod)
                    visitor.visitOuterClass(parentClassCodegen.type.internalName, method?.name, method?.descriptor)
                }
            }
        }

        abstract fun generateKotlinMetadataAnnotation()

        abstract fun bindFieldMetadata(field: IrField, fieldType: Type, fieldName: String)

        abstract fun bindMethodMetadata(method: IrFunction, signature: Method)
    }
}

private fun IrFile.loadSourceFilesInfo(context: JvmBackendContext): List<File> {
    val entry = fileEntry
    if (entry is MultifileFacadeFileEntry) {
        return entry.partFiles.flatMap { it.loadSourceFilesInfo(context) }
    }
    return listOfNotNull(context.psiSourceManager.getFileEntry(this)?.let { File(it.name) })
}

private fun JvmClassSignature.hasInvalidName() =
    name.splitToSequence('/').any { identifier -> identifier.any { it in JvmSimpleNameBacktickChecker.INVALID_CHARS } }

private val IrClass.flags: Int
    get() = origin.flags or getVisibilityAccessFlagForClass() or deprecationFlags or when {
        isAnnotationClass -> Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        isInterface -> Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        isEnumClass -> Opcodes.ACC_ENUM or Opcodes.ACC_SUPER or modality.flags
        else -> Opcodes.ACC_SUPER or modality.flags
    }

private val IrField.flags: Int
    get() = origin.flags or visibility.flags or (correspondingPropertySymbol?.owner?.deprecationFlags ?: 0) or
            (if (isFinal) Opcodes.ACC_FINAL else 0) or
            (if (isStatic) Opcodes.ACC_STATIC else 0) or
            (if (hasAnnotation(VOLATILE_ANNOTATION_FQ_NAME)) Opcodes.ACC_VOLATILE else 0) or
            (if (hasAnnotation(TRANSIENT_ANNOTATION_FQ_NAME)) Opcodes.ACC_TRANSIENT else 0) or
            (if (hasAnnotation(JVM_SYNTHETIC_ANNOTATION_FQ_NAME)) Opcodes.ACC_SYNTHETIC else 0)

private val IrDeclarationOrigin.flags: Int
    get() = (if (isSynthetic) Opcodes.ACC_SYNTHETIC else 0) or
            (if (this == IrDeclarationOrigin.FIELD_FOR_ENUM_ENTRY) Opcodes.ACC_ENUM else 0)

private val Modality.flags: Int
    get() = when (this) {
        Modality.ABSTRACT, Modality.SEALED -> Opcodes.ACC_ABSTRACT
        Modality.FINAL -> Opcodes.ACC_FINAL
        Modality.OPEN -> 0
        else -> throw AssertionError("Unsupported modality $this")
    }

private val Visibility.flags: Int
    get() = AsmUtil.getVisibilityAccessFlag(this) ?: throw AssertionError("Unsupported visibility $this")

internal val IrDeclaration.OtherOrigin: JvmDeclarationOrigin
    get() {
        val klass = (this as? IrClass) ?: parentAsClass
        return OtherOrigin(
            // For declarations inside lambdas, produce a descriptor which refers back to the original function.
            // This is needed for plugins which check for lambdas inside of inline functions using the descriptor
            // contained in JvmDeclarationOrigin. This matches the behavior of the JVM backend.
            if (klass.origin == JvmLoweredDeclarationOrigin.LAMBDA_IMPL || klass.origin == JvmLoweredDeclarationOrigin.SUSPEND_LAMBDA) {
                klass.attributeOwnerId.safeAs<IrFunctionReference>()?.symbol?.descriptor ?: descriptor
            } else {
                descriptor
            }
        )
    }

private fun IrClass.getSuperClassInfo(typeMapper: IrTypeMapper): IrSuperClassInfo {
    if (isInterface) {
        return IrSuperClassInfo(AsmTypes.OBJECT_TYPE, null)
    }

    for (superType in superTypes) {
        val superClass = superType.safeAs<IrSimpleType>()?.classifier?.safeAs<IrClassSymbol>()?.owner
        if (superClass != null && !superClass.isJvmInterface) {
            return IrSuperClassInfo(typeMapper.mapClass(superClass), superType)
        }
    }

    return IrSuperClassInfo(AsmTypes.OBJECT_TYPE, null)
}
