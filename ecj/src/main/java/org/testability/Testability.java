package org.testability;

import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
        boolean needsCodeReplace = classDeclaration.allCallsToRedirect.containsKey(toUniqueMethodDescriptor(thisMessageSend));

        if (!needsCodeReplace)
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

        messageGetField.binding.parameters = Arrays.copyOf(messageSend.binding.parameters,messageSend.binding.parameters.length + 1);
        System.arraycopy(messageGetField.binding.parameters, 0, messageGetField.binding.parameters, 1, messageGetField.binding.parameters.length -1);
        messageGetField.binding.parameters[0] = messageSend.receiver.resolvedType;

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

        //shift/insert receiver at pos 0
        int originalArgCount = messageSend.arguments == null ? 0 : messageSend.arguments.length;
        Expression[] argsWithReceiver = new Expression[originalArgCount + 1];
        for (int iArg = 0; iArg< originalArgCount; iArg++)
            argsWithReceiver[iArg + 1] = messageSend.arguments[iArg];

        argsWithReceiver[0] = messageSend.receiver;

        messageGetField.arguments = argsWithReceiver;

        return messageGetField;
    }
    /**
     * add a redirection via field to call - change the call so that it uses field and its 'apply' method
     * which, in turn makes the original call
     * @param allocationExpression
     * @param currentScope
     * @param codeStream
     * @param valueRequired
     * @return true if redirection was needed (and was added)
     */
    public static MessageSend replaceCallWithFieldRedirectorIfNeeded(AllocationExpression allocationExpression, BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
        TypeDeclaration classDeclaration = currentScope.methodScope().classScope().referenceContext;

        if (fromTestabilityFieldInitializerUsingSpecialLabel(currentScope))
            return null;

        //original message sent is rewired to localfield.apply()

        AllocationExpression thisAllocationExpression = allocationExpression;
        boolean needsCodeReplace = classDeclaration.allCallsToRedirect.containsKey(toUniqueMethodDescriptor(thisAllocationExpression));

        if (!needsCodeReplace)
            return null;

        //retarget the current message to generated local field'a apply() method. Arguments s/be the same, except boxing?

        MessageSend messageGetField = new MessageSend();

        String methodClassName = new String(allocationExpression.binding.declaringClass.readableName());

        String targetFieldNameInThis = testabilityFieldNameForNewOperator(methodClassName);
        String targetMethodName = "apply";

        messageGetField.selector = targetMethodName.toCharArray();
        messageGetField.binding =
                new MethodBinding(0, new TypeBinding[]{}, new ReferenceBinding[]{}, allocationExpression.binding.declaringClass);

        messageGetField.binding.modifiers = ClassFileConstants.AccPublic;
        messageGetField.binding.selector = messageGetField.selector;

        messageGetField.binding.parameters = Arrays.copyOf(allocationExpression.binding.parameters,
                allocationExpression.binding.parameters.length);

        messageGetField.binding.returnType = allocationExpression.resolvedType;
        messageGetField.binding.declaringClass = currentScope.classScope().referenceContext.binding;

        char[][] path = new char[1][];
        path[0] = targetFieldNameInThis.toCharArray();

        QualifiedNameReference qualifiedNameReference = new QualifiedNameReference(path, new long[path.length], 0, 0);
        qualifiedNameReference.resolve(currentScope);

        messageGetField.receiver = qualifiedNameReference;

        if (null == messageGetField.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + qualifiedNameReference);//TODO handle legally

        messageGetField.actualReceiverType = messageGetField.receiver.resolvedType;

        //shift/insert receiver at pos 0
        int originalArgCount = allocationExpression.arguments == null ? 0 : allocationExpression.arguments.length;
        Expression[] argsCopy = new Expression[originalArgCount];
        for (int iArg = 0; iArg< originalArgCount; iArg++)
            argsCopy[iArg] = allocationExpression.arguments[iArg];

        messageGetField.arguments = argsCopy;

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
            classReferenceContext.allCallsToRedirect.put(toUniqueMethodDescriptorMessageSend(messageSend), messageSend);
        }
    }
    public static void registerCallToRedirectIfNeeded(AllocationExpression allocationExpression, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (!fromTestabilityFieldInitializer(scope)) {//it calls original code
            classReferenceContext.allCallsToRedirect.put(toUniqueMethodDescriptorAllocationExpression(allocationExpression), allocationExpression);
        }
    }


    public static List<FieldDeclaration> makeTestabilityRedirectorFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding){

        return typeDeclaration.allCallsToRedirect.values().stream().
                map(originalCall -> {
                            FieldDeclaration fieldDeclaration = null;

                            if (originalCall instanceof MessageSend) {
                                MessageSend originalMessageSend = (MessageSend) originalCall;
                                char[] invokedMethodName = originalMessageSend.selector;
                                String invokedClassName = fullyQualifiedFromCompoundName(originalMessageSend.binding.declaringClass.compoundName);
                                String fieldName = testabilityFieldNameForExternalAccess(invokedClassName, invokedMethodName);

                                fieldDeclaration = makeRedirectorFieldDeclaration(
                                        typeDeclaration,
                                        referenceBinding,
                                        originalMessageSend,
                                        fieldName);
                            } else if (originalCall instanceof AllocationExpression) {
                                AllocationExpression originalAllocationExpression = (AllocationExpression) originalCall;

                                String invokedClassName = fullyQualifiedFromCompoundName(originalAllocationExpression.binding.declaringClass.compoundName);
                                String fieldName = testabilityFieldNameForNewOperator(invokedClassName);

                                fieldDeclaration = makeRedirectorFieldDeclaration(
                                        typeDeclaration,
                                        referenceBinding,
                                        originalAllocationExpression,
                                        fieldName);
                            }
                            return fieldDeclaration;
                        }).
                filter(Objects::nonNull).
                peek(fieldDeclaration -> fieldDeclaration.resolve(typeDeclaration.initializerScope)).
                collect(toList());
    }

    static String toUniqueMethodDescriptorMessageSend(MessageSend m) {
            return m.receiver.toString() + "." + m.binding.toString();
    }
    static String toUniqueMethodDescriptorAllocationExpression(AllocationExpression m) {
        return  "new" + m.binding.toString();
    }
    static String toUniqueMethodDescriptor(Expression m) {
        if (m instanceof MessageSend)
            return toUniqueMethodDescriptorMessageSend((MessageSend) m);
        else if (m instanceof AllocationExpression)
            return toUniqueMethodDescriptorAllocationExpression((AllocationExpression) m);
        else return "";
    }

    static FieldDeclaration makeRedirectorFieldDeclaration(TypeDeclaration typeDeclaration, SourceTypeBinding referenceBinding, MessageSend originalMessageSend, String fieldName) {
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
                        1 :
                        originalMessageSend.arguments.length + 1)
                ).
                toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        TypeBinding[] typeArguments; //receiver, args, return
        if (originalMessageSend.arguments == null) {
            typeArguments = new TypeBinding[]{originalMessageSend.receiver.resolvedType, fieldTypeBinding};
        } else {
            typeArguments = new TypeBinding[originalMessageSend.arguments.length + 2];
            typeArguments[0] = originalMessageSend.receiver.resolvedType;

            int iArg = 1;
            for (Expression arg : originalMessageSend.arguments) {
                typeArguments[iArg++] = boxIfApplicable(arg.resolvedType, lookupEnvironment);
            }
            typeArguments[iArg++] = fieldTypeBinding;
        }

        ParameterizedTypeBinding typeBinding =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArguments,
                        referenceBinding);

        TypeReference[][] typeReferences = new TypeReference[path.length][];
        typeReferences[path.length - 1] = Arrays.stream(typeArguments).
                map(type -> Testability.boxIfApplicable(type, lookupEnvironment)).
                map(Testability::typeReferenceFromTypeBinding).
                collect(toList()).
                toArray(new TypeReference[0]);

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

        int argc = typeArguments.length - 1; //type args has return at the end, method args do not //Optional.ofNullable(originalMessageSend.arguments).map(ex -> ex.length).orElse(0);//this.descriptor.parameters.length;
        Argument[] arguments = new Argument[argc];
        for (int i = 0; i < argc; i++) {
            TypeBinding typeBindingForArg = boxIfApplicable(typeBinding.arguments[i], lookupEnvironment);
            TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
            arguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
        }

        lambdaExpression.setArguments(arguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(fieldDeclaration.type.resolvedType);

        MessageSend messageSendInLambdaBody = new MessageSend();

        messageSendInLambdaBody.selector = originalMessageSend.selector;

        Expression newReceiver = new SingleNameReference((" arg0").toCharArray(), 0);

        messageSendInLambdaBody.receiver = newReceiver;

        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = originalMessageSend.binding;

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        boolean receiverPrecedesParameters = true;
        int parameterShift = receiverPrecedesParameters ? 1 : 0;
        Expression[] argv = new SingleNameReference[argc - parameterShift];
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + parameterShift)).toCharArray();//arguments[i].name;//CharOperation.append(ImplicitArgName, Integer.toString((i + parameterShift)).toCharArray());

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);
            argv[i] = singleNameReference;
        }

        messageSendInLambdaBody.arguments = argv;

        Block block = new Block(2);
        LabeledStatement labeledStatement = new LabeledStatement(
                "testabilitylabel".toCharArray(),
                new EmptyStatement(0, 0),
                0, 0);

        if (messageSendInLambdaBody.binding.returnType instanceof VoidTypeBinding) {
            Expression nullExpression = new NullLiteral(0, 0);
            ReturnStatement returnStatement = new ReturnStatement(nullExpression, 0, 0);

            block.statements = new Statement[]{
                    labeledStatement,
                    messageSendInLambdaBody,
                    returnStatement
            };

        } else {

            ReturnStatement returnStatement = new ReturnStatement(messageSendInLambdaBody, 0, 0);

            block.statements = new Statement[]{
                    labeledStatement,
                    returnStatement
            };
        }

        lambdaExpression.setBody(block);

        fieldDeclaration.initialization = lambdaExpression;
        return fieldDeclaration;
    }
    static FieldDeclaration makeRedirectorFieldDeclaration(TypeDeclaration typeDeclaration, SourceTypeBinding referenceBinding, AllocationExpression originalMessageSend, String fieldName) {
        TypeBinding fieldTypeBinding = //TypeReference.baseTypeReference(TypeIds.T_int, 0).resolveType(currentBinding.scope);
                originalMessageSend.binding.declaringClass;

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

        TypeBinding[] typeArguments; //args, return
        if (originalMessageSend.arguments == null) {
            typeArguments = new TypeBinding[]{fieldTypeBinding};
        } else {
            typeArguments = new TypeBinding[originalMessageSend.arguments.length + 1];

            int iArg = 0;
            for (Expression arg : originalMessageSend.arguments) {
                typeArguments[iArg++] = boxIfApplicable(arg.resolvedType, lookupEnvironment);
            }
            typeArguments[iArg++] = fieldTypeBinding;
        }

        ParameterizedTypeBinding typeBinding =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArguments,
                        referenceBinding);

        TypeReference[][] typeReferences = new TypeReference[path.length][];
        typeReferences[path.length - 1] = Arrays.stream(typeArguments).
                map(type -> Testability.boxIfApplicable(type, lookupEnvironment)).
                map(Testability::typeReferenceFromTypeBinding).
                collect(toList()).
                toArray(new TypeReference[0]);

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

        int argc = typeArguments.length - 1; //type args has return at the end, method args do not //Optional.ofNullable(originalMessageSend.arguments).map(ex -> ex.length).orElse(0);//this.descriptor.parameters.length;
        Argument[] arguments = new Argument[argc];
        for (int i = 0; i < argc; i++) {
            TypeBinding typeBindingForArg = boxIfApplicable(typeBinding.arguments[i], lookupEnvironment);
            TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
            arguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
        }

        lambdaExpression.setArguments(arguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(fieldDeclaration.type.resolvedType);

        AllocationExpression messageSendInLambdaBody = new AllocationExpression();
        messageSendInLambdaBody.type = originalMessageSend.type;

//        messageSendInLambdaBody.selector = originalMessageSend.selector;

//        Expression newReceiver = new SingleNameReference((" arg0").toCharArray(), 0);
//
//        messageSendInLambdaBody.receiver = newReceiver;

        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = originalMessageSend.binding;

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        boolean receiverPrecedesParameters = false;
        int parameterShift = receiverPrecedesParameters ? 1 : 0;
        Expression[] argv = new SingleNameReference[argc - parameterShift];
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + parameterShift)).toCharArray();//arguments[i].name;//CharOperation.append(ImplicitArgName, Integer.toString((i + parameterShift)).toCharArray());

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);
            argv[i] = singleNameReference;
        }

        messageSendInLambdaBody.arguments = argv;

        Block block = new Block(2);
        LabeledStatement labeledStatement = new LabeledStatement(
                "testabilitylabel".toCharArray(),
                new EmptyStatement(0, 0),
                0, 0);

        ReturnStatement returnStatement = new ReturnStatement(messageSendInLambdaBody, 0, 0);

        block.statements = new Statement[]{
                labeledStatement,
                returnStatement
        };

        lambdaExpression.setBody(block);

        fieldDeclaration.initialization = lambdaExpression;
        return fieldDeclaration;
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
        if (typeBinding.dimensions() == 0){
            if (typeBinding instanceof BinaryTypeBinding) {
                BinaryTypeBinding binaryTypeBinding = (BinaryTypeBinding) typeBinding;
                return new QualifiedTypeReference(binaryTypeBinding.compoundName, new long[binaryTypeBinding.compoundName.length]);
            }
            return new SingleTypeReference(
                    typeBinding.sourceName(), 0
            );

        } else {
            return new ArrayTypeReference(
                    typeBinding.sourceName(),
                    typeBinding.dimensions() - 1,
                    typeBinding.id
            );
        }
    }
    static public TypeBinding boxIfApplicable(TypeBinding typeBinding, LookupEnvironment lookupEnvironment) {
        if (typeBinding instanceof BaseTypeBinding){
            BaseTypeBinding baseTypeBinding = (BaseTypeBinding) typeBinding;
            if (baseTypeBinding.isPrimitiveType()){
                //box the type
                return lookupEnvironment.computeBoxingType(typeBinding);
            }
        }
        return typeBinding;
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
    static public String testabilityFieldNameForNewOperator(String className) {
        return testabilityFieldNamePrefix + className.replace('.','$') + "$new";
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
