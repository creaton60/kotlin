/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetClass
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

class ClassReferenceLowering(val context: JsIrBackendContext) : FileLoweringPass {
    private val intrinsics = context.intrinsics

    private val primitiveClassesObject = context.primitiveClassesObject

    private val primitiveClassProperties = context.primitiveClassProperties

    private val booleanClass by lazy {
        primitiveClassProperties.singleOrNull { it.name == Name.identifier("booleanClass") }?.getter
            ?: primitiveClassesObject.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.special("<get-booleanClass>") }
    }
    private val intClass by lazy {
        primitiveClassProperties.singleOrNull { it.name == Name.identifier("intClass") }?.getter
            ?: primitiveClassesObject.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.special("<get-intClass>") }
    }
    private val doubleClass by lazy {
        primitiveClassProperties.singleOrNull { it.name == Name.identifier("doubleClass") }?.getter
            ?: primitiveClassesObject.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.special("<get-doubleClass>") }
    }

    private fun callGetKClassFromExpression(returnType: IrType, typeArgument: IrType, argument: IrExpression) =
        JsIrBuilder.buildCall(intrinsics.jsGetKClassFromExpression, returnType, listOf(typeArgument)).apply {
            putValueArgument(0, argument)
        }


    private fun getPrimitiveClass(target: IrSimpleFunction, returnType: IrType) =
        JsIrBuilder.buildCall(target.symbol, returnType).apply {
            dispatchReceiver = JsIrBuilder.buildGetObjectValue(primitiveClassesObject.owner.defaultType, primitiveClassesObject)
        }

    private fun callGetKClass(returnType: IrType, typeArgument: IrType) = when {
        typeArgument.isBoolean() -> getPrimitiveClass(booleanClass, returnType)
        typeArgument.isByte() -> getPrimitiveClass(intClass, returnType)
        typeArgument.isShort() -> getPrimitiveClass(intClass, returnType)
        typeArgument.isInt() -> getPrimitiveClass(intClass, returnType)
        typeArgument.isFloat() -> getPrimitiveClass(doubleClass, returnType)
        typeArgument.isDouble() -> getPrimitiveClass(doubleClass, returnType)
        else -> JsIrBuilder.buildCall(intrinsics.jsGetKClass, returnType, listOf(typeArgument)).apply {
            putValueArgument(0, callJsClass(typeArgument))
        }
    }

    private fun callJsClass(type: IrType) =
        JsIrBuilder.buildCall(intrinsics.jsClass, typeArguments = listOf(type))

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetClass(expression: IrGetClass) =
                callGetKClassFromExpression(
                    returnType = expression.type,
                    typeArgument = expression.argument.type,
                    argument = expression.argument.transform(this, null)
                )

            override fun visitClassReference(expression: IrClassReference) =
                callGetKClass(
                    returnType = expression.type,
                    typeArgument = expression.classType.makeNotNull(false)
                )
        })
    }
}

