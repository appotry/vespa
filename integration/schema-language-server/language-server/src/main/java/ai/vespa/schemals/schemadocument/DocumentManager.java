package ai.vespa.schemals.schemadocument;

import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;

import ai.vespa.schemals.tree.SchemaNode;

/**
 * DocumentManager
 * For each file the language server is responsible for there will be a corresponding class implementing DocumentManager which is responsible for parsing the file and getting diagnostics.
 */
public interface DocumentManager {

    public void updateFileContent(String content);
    public void updateFileContent(String content, Integer version);

    public void reparseContent();

    public boolean setIsOpen(boolean isOpen);
    public boolean getIsOpen();

    public SchemaNode getRootNode();

    public SchemaDocumentLexer lexer();

    public String getFileURI();

    public String getCurrentContent();

    public VersionedTextDocumentIdentifier getVersionedTextDocumentIdentifier();
}
