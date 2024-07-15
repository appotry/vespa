package ai.vespa.schemals.schemadocument.resolvers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import ai.vespa.schemals.common.FileUtils;
import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.index.Symbol.SymbolStatus;
import ai.vespa.schemals.index.Symbol.SymbolType;
import ai.vespa.schemals.schemadocument.ParseContext;
import ai.vespa.schemals.schemadocument.SchemaDocumentParser;
import ai.vespa.schemals.tree.SchemaNode;

public class InheritanceResolver {
    

    /*
     * Assuming first parsing step is done, use the list of unresolved inheritance
     * declarations to register the inheritance at index.
     * @return List of diagnostic found during inheritance handling
     * */
    public static List<Diagnostic> resolveInheritances(ParseContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> documentInheritanceURIs = new HashSet<>();

        for (SchemaNode inheritanceNode : context.unresolvedInheritanceNodes()) {
            if (inheritanceNode.getSymbol().getType() == SymbolType.DOCUMENT) {
                resolveDocumentInheritance(inheritanceNode, context, diagnostics).ifPresent(
                    parentURI -> documentInheritanceURIs.add(parentURI)
                );
            }
        }

        for (SchemaNode inheritanceNode : context.unresolvedInheritanceNodes()) {
            if (inheritanceNode.getSymbol().getType() == SymbolType.STRUCT) {
                resolveStructInheritance(inheritanceNode, context, diagnostics);
            }

            if (inheritanceNode.getSymbol().getType() == SymbolType.RANK_PROFILE) {
                resolveRankProfileInheritance(inheritanceNode, context, diagnostics);
            }
        }

        if (context.inheritsSchemaNode() != null) {
            String inheritsSchemaName = context.inheritsSchemaNode().getText();
            SchemaDocumentParser parent = context.schemaIndex().findSchemaDocumentWithName(inheritsSchemaName);
            if (parent != null) {
                if (!documentInheritanceURIs.contains(parent.getFileURI())) {
                    // TODO: quickfix
                    diagnostics.add(new Diagnostic(
                        context.inheritsSchemaNode().getRange(),
                        "The schema document must explicitly inherit from " + inheritsSchemaName + " because the containing schema does so.",
                        DiagnosticSeverity.Error,
                        ""
                    ));
                    context.schemaIndex().setSchemaInherits(context.fileURI(), parent.getFileURI());
                }
                context.schemaIndex().insertSymbolReference(context.fileURI(), context.inheritsSchemaNode());
            }
        }

        context.clearUnresolvedInheritanceNodes();

        return diagnostics;
    }

    private static void resolveStructInheritance(SchemaNode inheritanceNode, ParseContext context, List<Diagnostic> diagnostics) {
        SchemaNode myStructDefinitionNode = inheritanceNode.getParent().getPreviousSibling();
        String inheritedIdentifier = inheritanceNode.getText();

        if (myStructDefinitionNode == null) {
            return;
        }

        if (!myStructDefinitionNode.hasSymbol()) {
            return;
        }

        Symbol parentSymbol = context.schemaIndex().findSymbol(context.fileURI(), SymbolType.STRUCT, inheritedIdentifier);
        if (parentSymbol == null) {
            // Handled elsewhere
            return;
        }

        if (!context.schemaIndex().tryRegisterStructInheritance(myStructDefinitionNode.getSymbol(), parentSymbol)) {
            // TODO: get the chain?
            diagnostics.add(new Diagnostic(
                inheritanceNode.getRange(), 
                "Cannot inherit from " + parentSymbol.getShortIdentifier() + " because " + parentSymbol.getShortIdentifier() + " inherits from this struct.",
                DiagnosticSeverity.Error, 
                ""
            ));
        }


        // Look for redeclarations
        Set<String> fieldsSeen = new HashSet<>();

        for (Symbol fieldSymbol : context.schemaIndex().getAllStructFieldSymbols(myStructDefinitionNode.getSymbol())) {
            if (fieldsSeen.contains(fieldSymbol.getShortIdentifier())) {
                // TODO: quickfix
                diagnostics.add(new Diagnostic(
                    fieldSymbol.getNode().getRange(),
                    "struct " + myStructDefinitionNode.getText() + " cannot inherit from " + parentSymbol.getShortIdentifier() + " and redeclare field " + fieldSymbol.getShortIdentifier(),
                    DiagnosticSeverity.Error,
                    ""
                ));
            }
            fieldsSeen.add(fieldSymbol.getShortIdentifier().toLowerCase());
        }
    }

    private static void resolveRankProfileInheritance(SchemaNode inheritanceNode, ParseContext context, List<Diagnostic> diagnostics) {
        SchemaNode myRankProfileDefinitionNode = inheritanceNode.getParent().getPreviousSibling();
        String inheritedIdentifier = inheritanceNode.getText();

        if (myRankProfileDefinitionNode == null) return;
        if (!myRankProfileDefinitionNode.hasSymbol() || myRankProfileDefinitionNode.getSymbol().getStatus() != SymbolStatus.DEFINITION) return;

        if (inheritedIdentifier.equals("default")) {
            // TODO: mechanism for inheriting default rank profile. 
            // Workaround now: 
            inheritanceNode.setSymbolStatus(SymbolStatus.BUILTIN_REFERENCE);
            return;
        }


        List<Symbol> parentSymbols = context.schemaIndex().findAllSymbolsWithSchemaScope(context.fileURI(), SymbolType.RANK_PROFILE, inheritedIdentifier);

        if (parentSymbols.isEmpty()) {
            // Handled in resolve symbol ref
            return;
        }

        if (parentSymbols.size() > 1 && !parentSymbols.get(0).getFileURI().equals(context.fileURI())) {
            String note = "\nNote:";

            for (Symbol symbol : parentSymbols) {
                note += "\nDefined in " + FileUtils.fileNameFromPath(symbol.getFileURI());
            }

            diagnostics.add(new Diagnostic(
                inheritanceNode.getRange(),
                inheritedIdentifier + " is ambiguous in this context.",
                DiagnosticSeverity.Warning,
                note
            ));
        }

        // Choose last one, if more than one (undefined behaviour if ambiguous).
        Symbol parentSymbol = parentSymbols.get(0);

        Symbol definitionSymbol = myRankProfileDefinitionNode.getSymbol();
        if (!context.schemaIndex().tryRegisterRankProfileInheritance(definitionSymbol, parentSymbol)) {
            diagnostics.add(new Diagnostic(
                inheritanceNode.getRange(),
                "Cannot inherit from " + parentSymbol.getShortIdentifier() + " because " + parentSymbol.getShortIdentifier() + " inherits from this rank profile.", 
                DiagnosticSeverity.Error,
                ""
            ));

            return;
        }

        List<Symbol> parentDefinitions = context.schemaIndex().getAllRankProfileParents(definitionSymbol);

        /*
         * Look for colliding function names
         * TODO: other stuff than functions
         */
        Map<String, String> seenFunctions = new HashMap<>();
        for (Symbol parentDefinition : parentDefinitions) {
            if (parentDefinition.equals(definitionSymbol)) continue;

            List<Symbol> functionDefinitionsInParent = context.schemaIndex().getAllRankProfileFunctions(parentDefinition);

            context.logger().println("PROFILE " + parentDefinition.getLongIdentifier());
            for (Symbol func : functionDefinitionsInParent) {
                context.logger().println("    FUNC: " + func.getLongIdentifier());
                if (seenFunctions.containsKey(func.getShortIdentifier())) {
                    // TODO: quickfix
                    diagnostics.add(new Diagnostic(
                        inheritanceNode.getRange(),
                        "Cannot inherit from " + parentSymbol.getShortIdentifier() + " because " + parentSymbol.getShortIdentifier() + 
                        " defines function " + func.getShortIdentifier() + " which is already defined in " + seenFunctions.get(func.getShortIdentifier()),
                        DiagnosticSeverity.Error,
                        ""
                    ));
                }
                seenFunctions.put(func.getShortIdentifier(), parentDefinition.getLongIdentifier());
            }
        }
    }

    private static Optional<String> resolveDocumentInheritance(SchemaNode inheritanceNode, ParseContext context, List<Diagnostic> diagnostics) {
        String schemaDocumentName = inheritanceNode.getText();
        SchemaDocumentParser parent = context.schemaIndex().findSchemaDocumentWithName(schemaDocumentName);
        if (parent == null) {
            // Handled in resolve symbol references
            return Optional.empty();
        }
        if (!context.schemaIndex().tryRegisterDocumentInheritance(context.fileURI(), parent.getFileURI())) {
            // Inheritance cycle
            // TODO: quickfix, get the chain?
            diagnostics.add(new Diagnostic(
                inheritanceNode.getRange(),
                "Cannot inherit from " + schemaDocumentName + " because " + schemaDocumentName + " inherits from this document.",
                DiagnosticSeverity.Error,
                ""
            ));
            return Optional.empty();
        }

        inheritanceNode.setSymbolStatus(SymbolStatus.REFERENCE);
        context.schemaIndex().insertSymbolReference(context.fileURI(), inheritanceNode);
        return Optional.of(parent.getFileURI());
    }
}
