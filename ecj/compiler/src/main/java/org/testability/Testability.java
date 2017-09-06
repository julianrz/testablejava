package org.testability;


import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class Testability {
    public static final String testabilityFieldNamePrefix = "$$";
    public static final String TARGET_REDIRECTED_METHOD_NAME = "apply";
    public static final String TESTABILITYLABEL = "testabilitylabel";

    /**
     *
     * @param currentScope
     * @param expressionToBeReplaced
     * @return truen if original expression needs to be redirected using field/apply
     */
    static boolean needsCodeReplace(BlockScope currentScope, Expression expressionToBeReplaced){
        MethodScope methodScope = currentScope.methodScope();
        if (methodScope == null) //TODO could it be block scope?
            return false;

        if (methodScope.isStatic)
            return false; //TODO remove when implemented

        if (isLabelledAsDontRedirect(methodScope, expressionToBeReplaced))
            return false;
        TypeDeclaration classDeclaration = methodScope.classScope().referenceContext;

        if (fromTestabilityFieldInitializerUsingSpecialLabel(currentScope))
            return false;

        String key = toUniqueMethodDescriptor(expressionToBeReplaced);

        return classDeclaration.allCallsToRedirect.containsKey(key);
    }

    /**
     *
     * @param methodScope
     * @param expressionToBeReplaced
     * @return true if the given expression or any of its parents marked with dontredirect: label
     */
    static boolean isLabelledAsDontRedirect(MethodScope methodScope, Expression expressionToBeReplaced) {
        //get to list of statements for method, find current expression, see if it is under a labelled statement
        if (!(methodScope.referenceContext instanceof MethodDeclaration))
            return false;

        MethodDeclaration declaration = (MethodDeclaration) methodScope.referenceContext;
        List<LabeledStatement> labelledStatementsDontRedirect = new ArrayList<>();

        declaration.traverse(new ASTVisitor() {
            @Override
            public void endVisit(LabeledStatement labeledStatement, BlockScope scope) {
                System.out.println(labeledStatement);
                if (new String(labeledStatement.label).startsWith("dontredirect"))
                    labelledStatementsDontRedirect.add(labeledStatement);
                super.endVisit(labeledStatement, scope);
            }

        }, methodScope.classScope());

        //traverse each labelled statement to find our expressionToBeReplaced
        //if found, the expression is marked with dontredirect label, return true

        AtomicBoolean found = new AtomicBoolean(false);
        for (LabeledStatement labeledStatement : labelledStatementsDontRedirect) {

            labeledStatement.traverse(new ASTVisitor() {
                @Override
                public void endVisit(MessageSend messageSend, BlockScope scope) {
                    if (messageSend == expressionToBeReplaced)
                        found.set(true);
                    super.endVisit(messageSend, scope);
                }

                @Override
                public void endVisit(AllocationExpression allocationExpression, BlockScope scope) {
                    if (allocationExpression == expressionToBeReplaced)
                        found.set(true);
                    super.endVisit(allocationExpression, scope);
                }
            }, methodScope);
        }

        return found.get();
    }

    static MethodBinding makeRedirectorFieldMethodBinding(
            QualifiedNameReference newReceiver,
            MethodBinding originalBinding,
            BlockScope currentScope,
            Optional<TypeBinding> receiverType,
            InvocationSite invocationSite) {

        TypeBinding[] parameters;

        if (receiverType.isPresent()) {
            parameters = Arrays.copyOf(originalBinding.parameters, originalBinding.parameters.length + 1);
            System.arraycopy(parameters, 0, parameters, 1, parameters.length - 1);
            parameters[0] = receiverType.get();
        } else {
            parameters = Arrays.copyOf(originalBinding.parameters, originalBinding.parameters.length);
        }

        MethodBinding binding =
                currentScope.getMethod(
                        newReceiver.resolvedType,
                        TARGET_REDIRECTED_METHOD_NAME.toCharArray(),
                        parameters,
                        invocationSite);

        binding.modifiers = ClassFileConstants.AccPublic;
        binding.selector = TARGET_REDIRECTED_METHOD_NAME.toCharArray();

        return binding;
    }

    /**
     * add a redirection via field to call - change the call so that it uses field and its 'apply' method
     * which, in turn makes the original call
     * @param messageSend
     * @param currentScope
     * @param valueRequired
     * @return true if redirection was needed (and was added)
     */
    public static MessageSend replaceCallWithFieldRedirectorIfNeeded(MessageSend messageSend, BlockScope currentScope, boolean valueRequired) {
        if (!needsCodeReplace(currentScope, messageSend))
            return null;

        //retarget the current message to generated local field'a apply() method. Arguments s/be the same, except boxing?

        MessageSend messageToFieldApply = new MessageSend();

        String methodClassName = new String(messageSend.binding.declaringClass.readableName());

        String targetFieldNameInThis = testabilityFieldNameForExternalAccess(methodClassName, messageSend.selector);

        messageToFieldApply.selector = TARGET_REDIRECTED_METHOD_NAME.toCharArray();

        QualifiedNameReference qualifiedNameReference = makeQualifiedNameReference(targetFieldNameInThis);
        qualifiedNameReference.resolve(currentScope);

        messageToFieldApply.receiver = qualifiedNameReference;

        messageToFieldApply.binding = makeRedirectorFieldMethodBinding(
                qualifiedNameReference,
                messageSend.binding,
                currentScope,
                Optional.of(messageSend.receiver.resolvedType),
                messageSend);

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + qualifiedNameReference);//TODO handle legally

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        boolean receiverPrecedes = receiverPrecedesParameters(messageSend);
        int parameterShift = receiverPrecedes ? 1 : 0;

        //shift/insert receiver at pos 0
        int originalArgCount = messageSend.arguments == null ? 0 : messageSend.arguments.length;
        Expression[] argsWithReceiver = new Expression[parameterShift + originalArgCount];
        for (int iArg = 0; iArg< originalArgCount; iArg++) {
            argsWithReceiver[iArg + parameterShift] = messageSend.arguments[iArg];
            addBoxingIfNeeded(argsWithReceiver[iArg + parameterShift]);
        }
        if (receiverPrecedes)
            argsWithReceiver[0] = messageSend.receiver;

        messageToFieldApply.arguments = argsWithReceiver;

        if (valueRequired)
            messageToFieldApply.valueCast = messageSend.resolvedType;

        return messageToFieldApply;
    }

    /**
     * add a redirection via field to call - change the call so that it uses field and its 'apply' method
     * which, in turn makes the original call
     * @param allocationExpression
     * @param currentScope
     * @param valueRequired
     * @return true if redirection was needed (and was added)
     */
    public static MessageSend replaceCallWithFieldRedirectorIfNeeded(AllocationExpression allocationExpression, BlockScope currentScope, boolean valueRequired) {

        if (!needsCodeReplace(currentScope, allocationExpression))
            return null;

        //retarget the current message to generated local field'a apply() method. Arguments s/be the same, except boxing?

        MessageSend messageToFieldApply = new MessageSend();

        String methodClassName = new String(allocationExpression.binding.declaringClass.readableName());

        String targetFieldNameInThis = testabilityFieldNameForNewOperator(methodClassName);

        messageToFieldApply.selector = TARGET_REDIRECTED_METHOD_NAME.toCharArray();

        QualifiedNameReference qualifiedNameReference = makeQualifiedNameReference(targetFieldNameInThis);
        qualifiedNameReference.resolve(currentScope);

        messageToFieldApply.receiver = qualifiedNameReference;

        messageToFieldApply.binding = makeRedirectorFieldMethodBinding(
                qualifiedNameReference,
                allocationExpression.binding,
                currentScope,
                Optional.empty(),
                allocationExpression);

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + qualifiedNameReference);//TODO handle legally

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        if (allocationExpression.arguments == null)
            messageToFieldApply.arguments = null;
        else {
            messageToFieldApply.arguments = Arrays.copyOf(allocationExpression.arguments, allocationExpression.arguments.length);
            for (Expression arg : messageToFieldApply.arguments) {
                addBoxingIfNeeded(arg);
            }
        }

        if (valueRequired)
            messageToFieldApply.valueCast = allocationExpression.resolvedType;

        return messageToFieldApply;
    }

    static void addBoxingIfNeeded(Expression expression) {
        if (expression.resolvedType instanceof BaseTypeBinding) //Function.apply always takes boxed types
            expression.implicitConversion =
                    TypeIds.BOXING |
                            (expression.resolvedType.id<<4); //in case it is primitive type //TODO why contains integer conversion when char?
    }

    static QualifiedNameReference makeQualifiedNameReference(String targetFieldNameInThis) {
        char[][] path = new char[1][];
        path[0] = targetFieldNameInThis.toCharArray();

        return new QualifiedNameReference(path, new long[path.length], 0, 0);
    }

    public static void registerCallToRedirectIfNeeded(MessageSend messageSend, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (
//                    !classReferenceContext.isTestabilityRedirectorField(this.receiver) &&
                !fromTestabilityFieldInitializer(scope) && //it calls original code
//                    !classReferenceContext.isTestabilityRedirectorMethod(scope) &&
                !isTestabilityFieldAccess(messageSend.receiver) &&
                        (scope.methodScope()!=null && !scope.methodScope().isStatic) //TODO remove when implemented
                ) //it calls the testability field apply method

        {
            classReferenceContext.allCallsToRedirect.put(toUniqueMethodDescriptorMessageSend(messageSend), messageSend);
        }
    }
    public static void registerCallToRedirectIfNeeded(AllocationExpression allocationExpression, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (!fromTestabilityFieldInitializer(scope) &&
            (scope.methodScope()!=null && !scope.methodScope().isStatic) //TODO remove when implemented
           ) {//it calls original code
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
                                        originalMessageSend, typeDeclaration,
                                        referenceBinding,
                                        fieldName);
                            } else if (originalCall instanceof AllocationExpression) {
                                AllocationExpression originalAllocationExpression = (AllocationExpression) originalCall;

                                String invokedClassName = fullyQualifiedFromCompoundName(originalAllocationExpression.binding.declaringClass.compoundName);
                                String fieldName = testabilityFieldNameForNewOperator(invokedClassName);

                                fieldDeclaration = makeRedirectorFieldDeclaration(
                                        originalAllocationExpression, typeDeclaration,
                                        referenceBinding,
                                        fieldName);
                            }
                            return fieldDeclaration;
                        }).
                filter(Objects::nonNull).
                peek(fieldDeclaration -> fieldDeclaration.resolve(typeDeclaration.initializerScope)).
                peek(fieldDeclaration -> {
                    System.out.println("injected redirector field: " + fieldDeclaration);
                }).
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

    static LambdaExpression makeLambdaExpression(
            MessageSend originalMessageSend,
            TypeDeclaration typeDeclaration,
            ParameterizedQualifiedTypeReference typeReferenceForFunction,
            LookupEnvironment lookupEnvironment,
            TypeBinding[] typeArgumentsForFunction,
            ParameterizedTypeBinding typeBindingForFunction) {

        LambdaExpression lambdaExpression = new LambdaExpression(typeDeclaration.compilationResult, false);
        //see ReferenceExpression::generateImplicitLambda

        int argc = typeArgumentsForFunction.length - 1;

        Argument[] lambdaArguments = new Argument[argc];
        for (int i = 0; i < argc; i++) { //type args has return at the end, method args do not
            TypeBinding typeBindingForArg = boxIfApplicable(typeBindingForFunction.arguments[i], lookupEnvironment);
            TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
            lambdaArguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
        }

        lambdaExpression.setArguments(lambdaArguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(typeReferenceForFunction.resolvedType);

        return lambdaExpression;
    }

    static FieldDeclaration makeRedirectorFieldDeclaration(
            MessageSend originalMessageSend,
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        TypeBinding fieldTypeBinding = originalMessageSend.binding.returnType;

        if (new String(fieldTypeBinding.shortReadableName()).equals("void")) {
            TypeBinding tvoid = new SingleTypeReference("Void".toCharArray(), -1).resolveType(referenceBinding.scope);
            fieldTypeBinding = tvoid;
        }

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        int parameterShift = receiverPrecedesParameters(originalMessageSend) ? 1 : 0;

        char[][] path = {
            functionNameForArgs(originalMessageSend, originalMessageSend.arguments).toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException("testablejava internal error, " + new String(path[0]) + " not found");
        }

        Expression[] originalArguments = originalMessageSend.arguments;
        if (originalArguments == null)
            originalArguments = new Expression[0];

        TypeBinding[] typeArgumentsForFunction //(receiver), args, return
                   = new TypeBinding[parameterShift + originalArguments.length + 1];

        if (parameterShift > 0)
            typeArgumentsForFunction[0] = originalMessageSend.receiver.resolvedType;

        int iArg = parameterShift;
        for (TypeBinding arg : originalMessageSend.binding.parameters) {
            typeArgumentsForFunction[iArg++] = boxIfApplicable(arg, lookupEnvironment);//boxIfApplicable(arg.resolvedType, lookupEnvironment);
        }
        typeArgumentsForFunction[iArg++] = fieldTypeBinding;

        ParameterizedTypeBinding typeBindingForFunction =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArgumentsForFunction,
                        referenceBinding);

        TypeReference[][] typeReferences = new TypeReference[path.length][];
        typeReferences[path.length - 1] = Arrays.stream(typeArgumentsForFunction).
                map(type -> Testability.boxIfApplicable(type, lookupEnvironment)).
                map(Testability::typeReferenceFromTypeBinding).
                collect(toList()).
                toArray(new TypeReference[0]);

        ParameterizedQualifiedTypeReference parameterizedQualifiedTypeReferenceForFunction = new ParameterizedQualifiedTypeReference(
                path,
                typeReferences,
                0,
                new long[path.length]);

        //TODO maketypereference

        fieldDeclaration.type = parameterizedQualifiedTypeReferenceForFunction;

        FieldBinding fieldBinding = new
                FieldBinding(
                fieldDeclaration, typeBindingForFunction, fieldDeclaration.modifiers /*| ClassFileConstants.AccStatic*/ /*| ExtraCompilerModifiers.AccUnresolved*/, typeDeclaration.binding);//sourceType);

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed?

        LambdaExpression lambdaExpression = makeLambdaExpression(
                originalMessageSend,
                typeDeclaration,
                parameterizedQualifiedTypeReferenceForFunction,
                lookupEnvironment,
                typeArgumentsForFunction,
                typeBindingForFunction);

        MessageSend messageSendInLambdaBody = new MessageSend();
        messageSendInLambdaBody.selector = originalMessageSend.selector;

        //if receiver provided as lambda arg0, (arg0, arg1, .. argN) -> arg0.apply(arg1, .. argN)
        //otherwise (arg0, .. argN) -> (hardcodedReceiver, arg0, .. argN)

        if (receiverPrecedesParameters(originalMessageSend)){//originalMessageSend.receiver)) {
            Expression newReceiver = new SingleNameReference((" arg0").toCharArray(), 0);

            messageSendInLambdaBody.receiver = newReceiver;
        } else {
            messageSendInLambdaBody.receiver = originalMessageSend.receiver;
            //reset receiver type or it will re-resolve incorrectly to be of local field, and fail
            if (messageSendInLambdaBody.receiver instanceof NameReference)
                ((NameReference) messageSendInLambdaBody.receiver).actualReceiverType = null;
        }

        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = null;//this is to resolve expression without apparent receiver

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        Expression[] argv = new SingleNameReference[typeArgumentsForFunction.length - 1 - parameterShift];
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + parameterShift)).toCharArray();

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);
            argv[i] = singleNameReference;
        }

        messageSendInLambdaBody.arguments = argv;

        Block block = new Block(2);
        LabeledStatement labeledStatement = new LabeledStatement(
                TESTABILITYLABEL.toCharArray(),
                new EmptyStatement(0, 0),
                0, 0);

        if (originalMessageSend.binding.returnType instanceof VoidTypeBinding &&
                !originalMessageSend.binding.isConstructor()) { //constructor AllocationExpression has void return!
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

    static boolean receiverPrecedesParameters(MessageSend messageSend) {
        if (!(messageSend.actualReceiverType instanceof BinaryTypeBinding))
            return true;
        if (messageSend.binding.isStatic()) //receiver is hardcoded in lambda implementation
            return false;
        return true;
    }


    static FieldDeclaration makeRedirectorFieldDeclaration(
            AllocationExpression originalMessageSend,
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        TypeBinding fieldTypeBinding =
                originalMessageSend.binding.declaringClass;

        if (new String(fieldTypeBinding.shortReadableName()).equals("void")) {
            TypeBinding tvoid = new SingleTypeReference("Void".toCharArray(), -1).resolveType(referenceBinding.scope);
            fieldTypeBinding = tvoid;
        }

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        char[][] path = {
            functionNameForArgs(originalMessageSend.arguments, 0).toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException("testablejava internal error, " + new String(path[0]) + " not found");
        }

        Expression[] originalArguments = originalMessageSend.arguments;
        if (originalArguments == null)
            originalArguments = new Expression[0];

        TypeBinding[] typeArguments //args, return
                = new TypeBinding[originalArguments.length + 1];

        int iArg = 0;
        for (Expression arg : originalArguments) {
            typeArguments[iArg++] = boxIfApplicable(arg.resolvedType, lookupEnvironment);
        }
        typeArguments[iArg++] = fieldTypeBinding;

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

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

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
                TESTABILITYLABEL.toCharArray(),
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


    /**
     * Function name reflects # of its arguments, which is # of type args - 1
     * @param messageSend
     * @return
     */
    static String functionNameForArgs(MessageSend messageSend, Expression [] messageSendArguments) {
        int parameterShift = receiverPrecedesParameters(messageSend) ? 1 : 0;
        return functionNameForArgs(messageSendArguments, parameterShift);
    }

    static String functionNameForArgs(Expression [] arguments, int parameterShift) {
        int functionArgCount = (arguments == null? 0 : arguments.length) + parameterShift;
        return "Function" + functionArgCount;
    }

//    static boolean receiverPrecedesParameters(Expression messageSendReceiver) {
//        boolean ret = true;
//        if (messageSendReceiver instanceof NameReference && ((NameReference) messageSendReceiver).binding instanceof BinaryTypeBinding)
//            ret = false; //this is type reference line "Integer."
//        return ret;
//    }


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
