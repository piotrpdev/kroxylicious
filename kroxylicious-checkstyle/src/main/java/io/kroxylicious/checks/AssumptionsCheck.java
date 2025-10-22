/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.checks;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assumptions;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FullIdent;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

public class AssumptionsCheck extends AbstractCheck {

    private static final String MSG_MISSING_PREFERRED_EXCEPTION = "Must call Assumptions.setPreferredAssumptionException(Assumptions.JUNIT5) when using org.assertj.core.api.Assumptions.*";

    private boolean usedAssumptionMethod = false;
    private boolean setPreferredExceptionCalled = false;

    private static final List<String> ASSUMPTION_METHODS = Arrays.stream(Assumptions.class.getDeclaredMethods()).map(Method::getName)
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
        usedAssumptionMethod = false;
        setPreferredExceptionCalled = false;
    }

    @Override
    public void visitToken(DetailAST ast) {
        int type = ast.getType();

        if (type == TokenTypes.STATIC_IMPORT) {
            handleStaticImport(ast);
        }
        else if (type == TokenTypes.METHOD_CALL) {
            handleMethodCall(ast);
        }
    }

    private void handleStaticImport(DetailAST ast) {
        FullIdent importIdent = FullIdent.createFullIdentBelow(ast.getFirstChild());
        String importStr = importIdent.getText();

        if (importStr.startsWith("org.assertj.core.api.Assumptions")) {
            usedAssumptionMethod = true;
        }
    }

    private void handleMethodCall(DetailAST methodCall) {
        DetailAST dot = methodCall.findFirstToken(TokenTypes.DOT);
        DetailAST ident = methodCall.findFirstToken(TokenTypes.IDENT);

        if (dot != null) {
            String methodCallStr = dot.getText();
            String methodName = methodCall.getLastChild().getText();

            if (methodCallStr.equals("Assumptions") && ASSUMPTION_METHODS.contains(methodName)) {
                usedAssumptionMethod = true;
            }

            if (methodCallStr.equals("Assumptions") && methodName.equals("setPreferredAssumptionException")) {
                setPreferredExceptionCalled = true;
            }
        }
        else if (ident != null) {
            String methodName = ident.getText();
            if (ASSUMPTION_METHODS.contains(methodName)) {
                usedAssumptionMethod = true;
            }
            else if (methodName.equals("setPreferredAssumptionException")) {
                setPreferredExceptionCalled = true;
            }
        }
    }

    @Override
    public void finishTree(DetailAST rootAST) {
        if (usedAssumptionMethod && !setPreferredExceptionCalled) {
            log(rootAST, MSG_MISSING_PREFERRED_EXCEPTION);
        }
    }
}
