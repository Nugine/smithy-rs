/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators.error

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.RetryableTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.deprecatedShape
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.errorMetadata
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.unhandledError
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.customize.Section
import software.amazon.smithy.rust.codegen.core.smithy.customize.writeCustomizations
import software.amazon.smithy.rust.codegen.core.smithy.transformers.eventStreamErrors
import software.amazon.smithy.rust.codegen.core.smithy.transformers.operationErrors
import software.amazon.smithy.rust.codegen.core.util.UNREACHABLE
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase

/**
 * Generates a unified error enum for [operation]. [ErrorGenerator] handles generating the individual variants,
 * but we must still combine those variants into an enum covering all possible errors for a given operation.
 *
 * This generator also generates errors for event streams.
 */
class OperationErrorGenerator(
    private val model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val operationOrEventStream: Shape,
    private val customizations: List<ErrorCustomization>,
) {
    private val runtimeConfig = symbolProvider.config().runtimeConfig
    private val symbol = symbolProvider.toSymbol(operationOrEventStream)
    private val errorMetadata = errorMetadata(symbolProvider.config().runtimeConfig)
    private val createUnhandledError =
        RuntimeType.smithyHttp(runtimeConfig).resolve("result::CreateUnhandledError")

    private fun operationErrors(): List<StructureShape> =
        (operationOrEventStream as OperationShape).operationErrors(model).map { it.asStructureShape().get() }
    private fun eventStreamErrors(): List<StructureShape> =
        (operationOrEventStream as UnionShape).eventStreamErrors()
            .map { model.expectShape(it.asMemberShape().get().target, StructureShape::class.java) }

    fun render(writer: RustWriter) {
        val (errorSymbol, errors) = when (operationOrEventStream) {
            is OperationShape -> symbolProvider.symbolForOperationError(operationOrEventStream) to operationErrors()
            is UnionShape -> symbolProvider.symbolForEventStreamError(operationOrEventStream) to eventStreamErrors()
            else -> UNREACHABLE("OperationErrorGenerator only supports operation or event stream shapes")
        }

        val meta = RustMetadata(
            derives = setOf(RuntimeType.Debug),
            additionalAttributes = listOf(Attribute.NonExhaustive),
            visibility = Visibility.PUBLIC,
        )

        // TODO(deprecated): Remove this temporary alias. This was added so that the compiler
        // points customers in the right direction when they are upgrading. Unfortunately there's no
        // way to provide better backwards compatibility on this change.
        val kindDeprecationMessage = "Operation `*Error/*ErrorKind` types were combined into a single `*Error` enum. " +
            "The `.kind` field on `*Error` no longer exists and isn't needed anymore (you can just match on the " +
            "error directly since it's an enum now)."
        writer.rust(
            """
            /// Do not use this.
            ///
            /// $kindDeprecationMessage
            ##[deprecated(note = ${kindDeprecationMessage.dq()})]
            pub type ${errorSymbol.name}Kind = ${errorSymbol.name};
            """,
        )

        writer.rust("/// Error type for the `${errorSymbol.name}` operation.")
        meta.render(writer)
        writer.rustBlock("enum ${errorSymbol.name}") {
            errors.forEach { errorVariant ->
                documentShape(errorVariant, model)
                deprecatedShape(errorVariant)
                val errorVariantSymbol = symbolProvider.toSymbol(errorVariant)
                write("${errorVariantSymbol.name}(#T),", errorVariantSymbol)
            }
            rust(
                """
                /// An unexpected error occurred (e.g., invalid JSON returned by the service or an unknown error code).
                Unhandled(#T),
                """,
                unhandledError(runtimeConfig),
            )
        }
        writer.rustBlock("impl #T for ${errorSymbol.name}", createUnhandledError) {
            rustBlock(
                """
                fn create_unhandled_error(
                    source: Box<dyn std::error::Error + Send + Sync + 'static>,
                    meta: Option<#T>
                ) -> Self
                """,
                errorMetadata,
            ) {
                rust(
                    """
                    Self::Unhandled({
                        let mut builder = #T::builder().source(source);
                        builder.set_meta(meta);
                        builder.build()
                    })
                    """,
                    unhandledError(runtimeConfig),
                )
            }
        }
        writer.rustBlock("impl #T for ${errorSymbol.name}", RuntimeType.Display) {
            rustBlock("fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result") {
                delegateToVariants(errors) {
                    writable { rust("_inner.fmt(f)") }
                }
            }
        }

        val errorMetadataTrait = RuntimeType.provideErrorMetadataTrait(runtimeConfig)
        writer.rustBlock("impl #T for ${errorSymbol.name}", errorMetadataTrait) {
            rustBlock("fn meta(&self) -> &#T", errorMetadata(runtimeConfig)) {
                delegateToVariants(errors) {
                    writable { rust("#T::meta(_inner)", errorMetadataTrait) }
                }
            }
        }

        writer.writeCustomizations(customizations, ErrorSection.OperationErrorAdditionalTraitImpls(errorSymbol, errors))

        val retryErrorKindT = RuntimeType.retryErrorKind(symbolProvider.config().runtimeConfig)
        writer.rustBlock(
            "impl #T for ${errorSymbol.name}",
            RuntimeType.provideErrorKind(symbolProvider.config().runtimeConfig),
        ) {
            rustBlock("fn code(&self) -> Option<&str>") {
                rust("#T::code(self)", RuntimeType.provideErrorMetadataTrait(runtimeConfig))
            }

            rustBlock("fn retryable_error_kind(&self) -> Option<#T>", retryErrorKindT) {
                val retryableVariants = errors.filter { it.hasTrait<RetryableTrait>() }
                if (retryableVariants.isEmpty()) {
                    rust("None")
                } else {
                    rustBlock("match self") {
                        retryableVariants.forEach {
                            val errorVariantSymbol = symbolProvider.toSymbol(it)
                            rust("Self::${errorVariantSymbol.name}(inner) => Some(inner.retryable_error_kind()),")
                        }
                        rust("_ => None")
                    }
                }
            }
        }

        writer.rustBlock("impl ${errorSymbol.name}") {
            writer.rustTemplate(
                """
                /// Creates the `${errorSymbol.name}::Unhandled` variant from any error type.
                pub fn unhandled(err: impl Into<Box<dyn #{std_error} + Send + Sync + 'static>>) -> Self {
                    Self::Unhandled(#{Unhandled}::builder().source(err).build())
                }

                /// Creates the `${errorSymbol.name}::Unhandled` variant from a `#{error_metadata}`.
                pub fn generic(err: #{error_metadata}) -> Self {
                    Self::Unhandled(#{Unhandled}::builder().source(err.clone()).meta(err).build())
                }
                """,
                "error_metadata" to errorMetadata,
                "std_error" to RuntimeType.StdError,
                "Unhandled" to unhandledError(runtimeConfig),
            )
            writer.docs(
                """
                Returns error metadata, which includes the error code, message,
                request ID, and potentially additional information.
                """,
            )
            writer.rustBlock("pub fn meta(&self) -> &#T", errorMetadata) {
                rust("use #T;", RuntimeType.provideErrorMetadataTrait(runtimeConfig))
                rustBlock("match self") {
                    errors.forEach { error ->
                        val errorVariantSymbol = symbolProvider.toSymbol(error)
                        rust("Self::${errorVariantSymbol.name}(e) => e.meta(),")
                    }
                    rust("Self::Unhandled(e) => e.meta(),")
                }
            }
            errors.forEach { error ->
                val errorVariantSymbol = symbolProvider.toSymbol(error)
                val fnName = errorVariantSymbol.name.toSnakeCase()
                writer.rust("/// Returns `true` if the error kind is `${errorSymbol.name}::${errorVariantSymbol.name}`.")
                writer.rustBlock("pub fn is_$fnName(&self) -> bool") {
                    rust("matches!(self, Self::${errorVariantSymbol.name}(_))")
                }
            }
        }

        writer.rustBlock("impl #T for ${errorSymbol.name}", RuntimeType.StdError) {
            rustBlock("fn source(&self) -> Option<&(dyn #T + 'static)>", RuntimeType.StdError) {
                delegateToVariants(errors) {
                    writable {
                        rust("Some(_inner)")
                    }
                }
            }
        }
    }

    sealed class VariantMatch(name: String) : Section(name) {
        object Unhandled : VariantMatch("Unhandled")
        data class Modeled(val symbol: Symbol, val shape: Shape) : VariantMatch("Modeled")
    }

    /**
     * Generates code to delegate behavior to the variants, for example:
     *
     * ```rust
     *  match self {
     *      Self::InvalidGreeting(_inner) => inner.fmt(f),
     *      Self::ComplexError(_inner) => inner.fmt(f),
     *      Self::FooError(_inner) => inner.fmt(f),
     *      Self::Unhandled(_inner) => _inner.fmt(f),
     *  }
     *  ```
     *
     * [handler] is passed an instance of [VariantMatch]—a [writable] should be returned containing the content to be
     * written for this variant.
     *
     *  The field will always be bound as `_inner`.
     */
    fun RustWriter.delegateToVariants(
        errors: List<StructureShape>,
        handler: (VariantMatch) -> Writable,
    ) {
        rustBlock("match self") {
            errors.forEach {
                val errorSymbol = symbolProvider.toSymbol(it)
                rust("""Self::${errorSymbol.name}(_inner) => """)
                handler(VariantMatch.Modeled(errorSymbol, it))(this)
                write(",")
            }
            val unhandledHandler = handler(VariantMatch.Unhandled)
            rustBlock("Self::Unhandled(_inner) =>") {
                unhandledHandler(this)
            }
        }
    }
}
