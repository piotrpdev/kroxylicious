/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.checks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assumptions;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FullIdent;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

public class AssumptionsCheck extends AbstractCheck {

    private static final String MSG_MISSING_PREFERRED_EXCEPTION = "Must call Assumptions.setPreferredAssumptionException(PreferredAssumptionException.JUNIT5) when using org.assertj.core.api.Assumptions.*";

    private final ArrayList<Integer> assumptionLineNoList = new ArrayList<>();
    private boolean isAssumptionsUsed = false;
    private boolean isSetPreferredExceptionCalled = false;

    private static final List<String> ASSUMPTION_METHOD_NAMES = Arrays.stream(Assumptions.class.getDeclaredMethods()).map(Method::getName).distinct()
            .filter(methodName -> methodName.startsWith("assume")).toList();

    @Override
    public int[] getDefaultTokens() {
        return new int[]{
                TokenTypes.METHOD_CALL,
                TokenTypes.STATIC_IMPORT,
        };
    }

    @Override
    public int[] getAcceptableTokens() {
        return getDefaultTokens();
    }

    @Override
    public int[] getRequiredTokens() {
        return getDefaultTokens();
    }

    @Override
    public void beginTree(DetailAST rootAST) {
        isAssumptionsUsed = false;
        isSetPreferredExceptionCalled = false;
    }

    @Override
    public void visitToken(DetailAST ast) {
        int type = ast.getType();

        if (type == TokenTypes.STATIC_IMPORT || type == TokenTypes.IMPORT) {
            handleImport(ast);
        }
        else if (type == TokenTypes.METHOD_CALL) {
            handleMethodCall(ast);
        }
    }

    private void handleImport(DetailAST ast) {
        FullIdent importIdent = FullIdent.createFullIdentBelow(ast.getFirstChild());
        String importStr = importIdent.getText();

        if (importStr.startsWith("org.assertj.core.api.Assumptions")) {
            assumptionLineNoList.add(ast.getLineNo());
            isAssumptionsUsed = true;
        }
    }

    private void handleMethodCall(DetailAST ast) {
        DetailAST dot = ast.findFirstToken(TokenTypes.DOT);
        DetailAST ident = ast.findFirstToken(TokenTypes.IDENT);

        String methodName = "";

        if (dot != null) {
            methodName = ast.getLastChild().getText();
        }
        else if (ident != null) {
            methodName = ident.getText();
        }
        else {
            return;
        }

        if (ASSUMPTION_METHOD_NAMES.contains(methodName)) {
            assumptionLineNoList.add(ast.getLineNo());
            isAssumptionsUsed = true;
        }

        if (methodName.equals("setPreferredAssumptionException")) {
            isSetPreferredExceptionCalled = true;
        }
    }

    @Override
    public void finishTree(DetailAST rootAST) {
        if (isAssumptionsUsed && !isSetPreferredExceptionCalled) {
            for (int assumptionLineNo : assumptionLineNoList) {
                log(assumptionLineNo, MSG_MISSING_PREFERRED_EXCEPTION);
            }
        }
    }
}
