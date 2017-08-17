package org.testability;

import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class Testability {
    public static final String testabilityFieldNamePrefix = "$$";

    /**
     * add a redirection via field to call - change the call so that it uses field and its 'apply' method
     * which, in turn makes the original call
     * @param messageSend
     * @param currentScope
     * @param codeStream
     * @param valueRequired
     * @return true if redirection was needed (and was added)
     */
    public static MessageSend replaceCallWithFieldRedirectorIfNeeded(MessageSend messageSend, BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
        TypeDeclaration classDeclaration = currentScope.methodScope().classScope().referenceContext;

        if (fromTestabilityFieldInitializerUsingSpecialLabel(currentScope))
            return null;

        //original message sent is rewired to localfield.apply()

        MessageSend thisMessageSend = messageSend;
        Optional<MessageSend> needsCodeReplace = classDeclaration.allCallsToRedirect.stream().
                filter(ms -> ms == thisMessageSend).
                findFirst();//TODO could have just determined directly if replace is needed

        if (!needsCodeReplace.isPresent())
            return null;

        //retarget the current message to generated local field'a apply() method. Arguments s/be the same, except boxing?

        MessageSend messageGetField = new MessageSend();

        String methodClassName = new String(messageSend.binding.declaringClass.readableName());

        String targetFieldNameInThis = testabilityFieldNameForExternalAccess(methodClassName, messageSend.selector);
        String targetMethodName = "apply";

        messageGetField.selector = targetMethodName.toCharArray();
        messageGetField.binding =
                new MethodBinding(0, new TypeBinding[]{}, new ReferenceBinding[]{}, messageSend.binding.declaringClass);

        messageGetField.binding.modifiers = ClassFileConstants.AccPublic;
        messageGetField.binding.selector = messageGetField.selector;

        messageGetField.binding.parameters = messageSend.binding.parameters.clone();
        messageGetField.binding.returnType = messageSend.binding().returnType;
        messageGetField.binding.declaringClass = currentScope.classScope().referenceContext.binding;

        char[][] path = new char[1][];
        path[0] = targetFieldNameInThis.toCharArray();

        QualifiedNameReference qualifiedNameReference = new QualifiedNameReference(path, new long[path.length], 0, 0);
        qualifiedNameReference.resolve(currentScope);

        messageGetField.receiver = qualifiedNameReference;

        if (null == messageGetField.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + qualifiedNameReference);//TODO handle legally

        messageGetField.actualReceiverType = messageGetField.receiver.resolvedType;

        messageGetField.arguments = messageSend.arguments == null ? null : messageSend.arguments.clone();


        return messageGetField;
    }

    public static void registerCallToRedirectIfNeeded(MessageSend messageSend, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (
//                    !classReferenceContext.isTestabilityRedirectorField(this.receiver) &&
                !fromTestabilityFieldInitializer(scope) && //it calls original code
//                    !classReferenceContext.isTestabilityRedirectorMethod(scope) &&
                !isTestabilityFieldAccess(messageSend.receiver)) //it calls the testability field apply method

        {
            classReferenceContext.allCallsToRedirect.add(messageSend);
        }
    }

    public static List<FieldDeclaration> makeTestabilityRedirectorFields(
//            ClassFile classFile,
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding){

        List<FieldDeclaration> testabilityFieldDeclarations = new ArrayList<>();

        for (MessageSend originalMessageSend : typeDeclaration.allCallsToRedirect) {

            char[] invokedMethodName = originalMessageSend.selector;
            String invokedClassName = fullyQualifiedFromCompoundName(originalMessageSend.binding.declaringClass.compoundName);
            String fieldName = testabilityFieldNameForExternalAccess(invokedClassName, invokedMethodName);
            TypeBinding fieldTypeBinding = //TypeReference.baseTypeReference(TypeIds.T_int, 0).resolveType(currentBinding.scope);
                    originalMessageSend.binding.returnType;

            if (new String(fieldTypeBinding.shortReadableName()).equals("void")) {
                TypeBinding tvoid = new SingleTypeReference("Void".toCharArray(), -1).resolveType(referenceBinding.scope);
                fieldTypeBinding = tvoid;
            }

            FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

            LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

            char[][] path = {
                    ("Function" + (originalMessageSend.arguments == null ?
                            0 :
                            originalMessageSend.arguments.length)
                    ).
                            toCharArray()
            };

            ReferenceBinding genericType = lookupEnvironment.getType(path);

            TypeBinding[] typeArguments;
            if (originalMessageSend.arguments == null) {
                typeArguments = new TypeBinding[]{fieldTypeBinding};
            } else {
                typeArguments = new TypeBinding[originalMessageSend.arguments.length + 1];

                int iArg = 0;
                for (Expression arg : originalMessageSend.arguments) {
                    typeArguments[iArg++] = arg.resolvedType;
                }
                typeArguments[iArg++] = fieldTypeBinding;
            }

            ParameterizedTypeBinding typeBinding =
                    lookupEnvironment.createParameterizedType(
                            genericType,
                            typeArguments,
                            referenceBinding);

            TypeReference[][] typeReferences = new TypeReference[path.length][];
            typeReferences[path.length - 1] = new TypeReference[]{typeReferenceFromTypeBinding(fieldTypeBinding)};
            ParameterizedQualifiedTypeReference parameterizedQualifiedTypeReference = new ParameterizedQualifiedTypeReference(
                    path,
                    typeReferences,
                    0,
                    new long[path.length]);

            fieldDeclaration.type = parameterizedQualifiedTypeReference;

            FieldBinding fieldBinding = new
                    FieldBinding(
                    fieldDeclaration, typeBinding, fieldDeclaration.modifiers /*| ClassFileConstants.AccStatic*/ /*| ExtraCompilerModifiers.AccUnresolved*/, typeDeclaration.binding);//sourceType);

            fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature;

            fieldDeclaration.binding = fieldBinding;

            LambdaExpression lambdaExpression = new LambdaExpression(typeDeclaration.compilationResult, false);
            //see ReferenceExpression::generateImplicitLambda

            int argc = Optional.ofNullable(originalMessageSend.arguments).map(ex -> ex.length).orElse(0);//this.descriptor.parameters.length;
            Argument[] arguments = new Argument[argc];
            for (int i = 0; i < argc; i++) {
                TypeReference typeReference = typeReferenceFromTypeBinding(typeBinding.arguments[i]);
                arguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
            }
            lambdaExpression.setArguments(arguments);

            lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

            lambdaExpression.setExpectedType(fieldDeclaration.type.resolvedType);

            MessageSend messageSendInLambdaBody = new MessageSend();

            messageSendInLambdaBody.selector = originalMessageSend.selector;

            QualifiedNameReference newReceiver =
                    originalMessageSend.receiver instanceof QualifiedNameReference ?
                            new QualifiedNameReference( //clone
                                    ((QualifiedNameReference) originalMessageSend.receiver).tokens,
                                    ((QualifiedNameReference) originalMessageSend.receiver).sourcePositions,
                                    ((QualifiedNameReference) originalMessageSend.receiver).sourceStart,
                                    ((QualifiedNameReference) originalMessageSend.receiver).sourceEnd
                            ) : null; //TODO implement case when receiver is a constant

            messageSendInLambdaBody.receiver = newReceiver;

            messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
            messageSendInLambdaBody.binding = originalMessageSend.binding;

            //arguments need to be wired directly to lambda arguments, cause they can be constants, etc
            boolean receiverPrecedesParameters = false;//TODO
            int parameterShift = receiverPrecedesParameters ? 1 : 0;
            Expression[] argv = new SingleNameReference[argc - parameterShift];
            for (int i = 0, length = argv.length; i < length; i++) {
                char[] name = (" arg" + i).toCharArray();//arguments[i].name;//CharOperation.append(ImplicitArgName, Integer.toString((i + parameterShift)).toCharArray());

                SingleNameReference singleNameReference = new SingleNameReference(name, 0);

                singleNameReference.setExpressionContext(originalMessageSend.expressionContext);
                argv[i] = singleNameReference;
            }

            messageSendInLambdaBody.arguments = argv;

            Expression nullExpression = new NullLiteral(0, 0);
            ReturnStatement returnStatement = new ReturnStatement(nullExpression, 0, 0);
            Block block = new Block(2);
            LabeledStatement labeledStatement = new LabeledStatement(
                    "testabilitylabel".toCharArray(),
                    new EmptyStatement(0, 0),
                    0, 0);

            block.statements = new Statement[]{
                    labeledStatement,
                    messageSendInLambdaBody,
                    returnStatement
            };

            lambdaExpression.setBody(block);

            fieldDeclaration.initialization = lambdaExpression;

            fieldDeclaration.resolve(typeDeclaration.initializerScope);

            testabilityFieldDeclarations.add(fieldDeclaration);
        }

        return testabilityFieldDeclarations;
    }

    public static String fullyQualifiedFromCompoundName(char[][] compoundName) {
        return Arrays.stream(compoundName).map(pathEl -> new String(pathEl)).collect(joining("."));
    }

    public static String[] nArgFunctionsCode(int n) {
        String types = IntStream.range(0, n).mapToObj(Testability::toTType).collect(joining(","));
        String typesAndVars = IntStream.range(0, n).mapToObj(Testability::toTTypeAndVar).collect(joining(","));
        String code = String.format(
                "@FunctionalInterface\n" +
                        "public interface Function%d<%sR> {\n" +
                        "    R apply(%s);\n" +
                        "}",
                n,
                n == 0 ? "" : types + ",",
                typesAndVars);
        String fileName = "Function" + n + ".java";

        return new String[]{code, fileName};
    }
    static String toTTypeAndVar(int n) {
        return "T" + n + " t" + n;
    }

    static String toTType(int n) {
        return "T" + n;
    }

    public static ICompilationUnit[] makeFunctionNCompilationUnits() {
        return IntStream.range(0, 5).//255). //TODO reen, make fast!
                mapToObj(Testability::nArgFunctionsCode).
                map(codeAndFile -> new CompilationUnit(codeAndFile[0].toCharArray(), codeAndFile[1], null)).
                collect(toList()).
                toArray(new ICompilationUnit[0]);
    }

    static public TypeReference typeReferenceFromTypeBinding(TypeBinding typeBinding) {
        return typeBinding.dimensions() == 0 ?
                new SingleTypeReference(
                        typeBinding.sourceName(), 0
                ) :
                new ArrayTypeReference(
                        typeBinding.sourceName(),
                        typeBinding.dimensions() - 1,
                        typeBinding.id
                );
    }

    /**
     *
     * @param scope
     * @param classReferenceContext
     * @return testability field initializer is in scope
     */
    public static boolean fromTestabilityFieldInitializerUsingSpecialLabel(BlockScope scope) {
        MethodScope methodScope = scope.methodScope();
        if (methodScope == null) return false;

        ReferenceContext referenceContext = methodScope.referenceContext();
        if (referenceContext == null)
            return false;
        if (!(referenceContext instanceof LambdaExpression))
            return false;
        LambdaExpression lambdaExpression = (LambdaExpression) referenceContext;
        if (!(lambdaExpression.body instanceof Block))
            return false;
        Block block = (Block) lambdaExpression.body;
        if (block.statements.length<1)
            return false;
        if (!(block.statements[0] instanceof LabeledStatement))
            return false;
        LabeledStatement labeledStatement = (LabeledStatement) block.statements[0];
        return new String(labeledStatement.label).equals("testabilitylabel");
    }

    static public String testabilityFieldNameForExternalAccess(String methodClassName, char[] calledMethodName) {
        return testabilityFieldNamePrefix + methodClassName.replace('.','$') + "$" + new String(calledMethodName);
    }

    static public boolean isTestabilityRedirectorFieldName(String fieldName) {
        return fieldName.startsWith(testabilityFieldNamePrefix);
    }

    /**
     *
     * @param scope
     * @return if there is a field in (recursive) scope, return its name
     */
    static Optional<String> fieldNameFromScope(Scope scope) {
        if (scope == null)
            return Optional.empty();

        Optional<String> optName =
                Optional.ofNullable(scope.methodScope()).
                        map(methodScope -> methodScope.initializedField).
                        filter(f -> f != null).
                        map(f -> new String(f.name));
        if (optName.isPresent())
            return optName;

        return fieldNameFromScope(scope.parent);
    }

    /**
     *
     * @param scope
     * @param classReferenceContext
     * @return testability field initializer is in scope
     */
    public static boolean fromTestabilityFieldInitializer(BlockScope scope) {
        return fieldNameFromScope(scope).
                filter(Testability::isTestabilityRedirectorFieldName).
                isPresent();
    }

    public static boolean isTestabilityFieldAccess(Expression receiver) {
        if (! (receiver instanceof FieldReference))
            return false;
        FieldReference fieldReference = (FieldReference) receiver;
        return isTestabilityRedirectorFieldName(new String(fieldReference.token));
    }
}
