package org.testability;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.InstrumentationOptions;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.BranchLabel;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.*;
import static org.eclipse.jdt.internal.compiler.lookup.TypeIds.IMPLICIT_CONVERSION_MASK;

//TODO can we redirect things like "binding.enclosingType().readableName()" and avoid calling enclosingType for example(e.g can throw)? Could do a string->function map, but how to keep type safety?
//TODO how to test recursive functions?
public class Testability {
    public static final String TESTABILITY_FIELD_NAME_PREFIX = "$$";
    public static final String TESTABILITY_ARG_LIST_SEPARATOR = "$$";
    public static final String TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION = "apply";
    public static final String TARGET_REDIRECTED_METHOD_NAME_FOR_CONSUMER = "accept";
    public static final String TESTABILITYLABEL = "testabilitylabel"; //TODO can we use dontredirect: instead?
    public static final String DONTREDIRECT = "dontredirect";

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

        if (isLabelledAsDontRedirect(methodScope, expressionToBeReplaced))
            return false;
        TypeDeclaration classDeclaration = methodScope.classScope().referenceContext;

        if (fromTestabilityFieldInitializerUsingSpecialLabel(currentScope))
            return false;

        return classDeclaration.callExpressionToRedirectorField.containsKey(expressionToBeReplaced);
    }

    /**
     *
     * @param methodScope
     * @param expressionToBeReplaced
     * @return true if the given expression or any of its parents marked with dontredirect: label
     */
    static boolean isLabelledAsDontRedirect(MethodScope methodScope, Expression expressionToBeReplaced) {
        //get to list of statements for method, find current expression, see if it is under a labelled statement
        List<LabeledStatement> labelledStatementsDontRedirect = new ArrayList<>();

        if (methodScope.referenceContext instanceof AbstractMethodDeclaration) {
            AbstractMethodDeclaration declaration = (AbstractMethodDeclaration) methodScope.referenceContext;

            declaration.traverse(new ASTVisitor() {
                @Override
                public void endVisit(LabeledStatement labeledStatement, BlockScope scope) {
                    if (new String(labeledStatement.label).startsWith(DONTREDIRECT))
                        labelledStatementsDontRedirect.add(labeledStatement);
                    super.endVisit(labeledStatement, scope);
                }

            }, methodScope.classScope());
        } else if (methodScope.referenceContext instanceof Expression) {
            Expression declaration = (Expression) methodScope.referenceContext;
            declaration.traverse(new ASTVisitor() {
                @Override
                public void endVisit(LabeledStatement labeledStatement, BlockScope scope) {
                    if (new String(labeledStatement.label).startsWith(DONTREDIRECT))
                        labelledStatementsDontRedirect.add(labeledStatement);
                    super.endVisit(labeledStatement, scope);
                }

            }, methodScope.methodScope());
        } else {
            return false;
        }

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
            NameReference newReceiver,
            MethodBinding originalBinding,
            BlockScope currentScope,
            Optional<TypeBinding> receiverType,
            InvocationSite invocationSite,
            boolean returnsVoid) {

        TypeBinding[] parameters;

        if (receiverType.isPresent()) {
            parameters = Arrays.copyOf(originalBinding.parameters, originalBinding.parameters.length + 1);
            System.arraycopy(parameters, 0, parameters, 1, parameters.length - 1);
            parameters[0] = receiverType.get();
        } else {
            parameters = Arrays.copyOf(originalBinding.parameters, originalBinding.parameters.length);
        }

        String selector = returnsVoid?
                TARGET_REDIRECTED_METHOD_NAME_FOR_CONSUMER :
                TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION;

        MethodBinding binding =
                currentScope.getMethod(
                        newReceiver.resolvedType, //TODO may be unresolved? use receiverType passed in?
                        selector.toCharArray(),
                        parameters,
                        invocationSite);

        binding.modifiers = ClassFileConstants.AccPublic;
        if ((originalBinding.modifiers & ClassFileConstants.AccVarargs)!=0)
            binding.modifiers |= ClassFileConstants.AccVarargs;
        binding.selector = selector.toCharArray();

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

        TypeDeclaration typeDeclaration = currentScope.classScope().referenceContext;
        FieldDeclaration redirectorFieldDeclaration = typeDeclaration.callExpressionToRedirectorField.get(messageSend);

        MessageSend messageToFieldApply = new MessageSend();

        String targetFieldNameInThis = new String(redirectorFieldDeclaration.name);//testabilityFieldNameForExternalAccess(methodClassName, messageSend.selector);

        String selector = returnsVoid(messageSend)?
                        TARGET_REDIRECTED_METHOD_NAME_FOR_CONSUMER :
                        TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION;

        messageToFieldApply.selector = selector.toCharArray();//TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION.toCharArray();

        QualifiedNameReference qualifiedNameReference = makeQualifiedNameReference(targetFieldNameInThis);
        qualifiedNameReference.resolve(currentScope);

        boolean receiverPrecedes = receiverPrecedesParameters(messageSend);

        messageToFieldApply.receiver = qualifiedNameReference;

        messageToFieldApply.binding = makeRedirectorFieldMethodBinding( //TODO is this needed? see in addListenerCallsToConstructor, resolution sets this?
                qualifiedNameReference,
                messageSend.binding,
                currentScope,
                receiverPrecedes? Optional.of(messageSend.receiver.resolvedType) : Optional.empty(),
                messageSend,
                returnsVoid(messageSend));

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + qualifiedNameReference);//TODO handle legally

        messageToFieldApply.argumentTypes = messageToFieldApply.binding.parameters;//TODO original message has this, needed?

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        int parameterShift = receiverPrecedes ? 1 : 0;

        //shift/insert receiver at pos 0
        int originalArgCount = messageSend.arguments == null ? 0 : messageSend.arguments.length;
        Expression[] argsWithReceiver = new Expression[parameterShift + originalArgCount];
        for (int iArg = 0; iArg< originalArgCount; iArg++) {
            Expression arg = messageSend.arguments[iArg];
            TypeBinding targetParamType = messageSend.binding.parameters[iArg];

            ensureImplicitConversion(arg, targetParamType);

            argsWithReceiver[iArg + parameterShift] = arg;

        }
        if (receiverPrecedes)
            argsWithReceiver[0] = messageSend.receiver;

        messageToFieldApply.arguments = argsWithReceiver;

        if (valueRequired) {
            //valuecast is always needed because compiler emits lambda call (Object...):Object
            //in case where return is primitive type, need to find and cast to matching boxed type
            //unboxing will be a subsequent implicit conversion
            //TODO in mock situation it will be possible that lambda returns null, but in real code this will not be possible. Document
            messageToFieldApply.valueCast = boxIfApplicable(
                    messageSend.resolvedType,
                    currentScope.environment());
            if (messageSend.resolvedType instanceof BaseTypeBinding){
                addImplicitUnBoxing(messageToFieldApply, messageSend.resolvedType);
            }
        }

        diagnoseBinding(messageToFieldApply, currentScope);
        return messageToFieldApply;
    }

    static void ensureImplicitConversion(Expression arg, TypeBinding targetParamType) {
        removeCharToIntImplicitConversionIfNeeded(arg);

        addImplicitBoxingIfNeeded(arg, arg.resolvedType.equals(targetParamType)? arg.resolvedType : targetParamType);
    }


    static void diagnoseBinding(MessageSend messageSend, BlockScope currentScope) {
        MethodBinding binding = messageSend.binding;
        if (binding instanceof ProblemMethodBinding) {
            throw new RuntimeException("testability field method not found: " + binding + "; closest match: " + ((ProblemMethodBinding) binding).closestMatch);
        }

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

        //retarget the current message to generated local field'a apply() method. Arguments s/be the same, except boxing

        TypeDeclaration typeDeclaration = currentScope.classScope().referenceContext;
        FieldDeclaration redirectorFieldDeclaration = typeDeclaration.callExpressionToRedirectorField.get(allocationExpression);

        MessageSend messageToFieldApply = new MessageSend();

        String targetFieldNameInThis = new String(redirectorFieldDeclaration.name);//testabilityFieldNameForNewOperator(methodClassName);

        messageToFieldApply.selector = TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION.toCharArray(); //always Function for allocation

        NameReference fieldNameReference = makeSingleNameReference(targetFieldNameInThis);

        { //prevent diagnostic of invisible fields: Pb(75) Cannot reference a field before it is defined; useless here
            int savId = currentScope.methodScope().lastVisibleFieldID;
            currentScope.methodScope().lastVisibleFieldID = -1;

            fieldNameReference.resolve(currentScope);

            currentScope.methodScope().lastVisibleFieldID = savId;
        }

        messageToFieldApply.receiver = fieldNameReference;

        messageToFieldApply.binding = makeRedirectorFieldMethodBinding(
                fieldNameReference,
                allocationExpression.binding,
                currentScope,
                Optional.empty(),
                allocationExpression,
                false);

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + fieldNameReference);//TODO handle legally

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        if (allocationExpression.arguments == null)
            messageToFieldApply.arguments = null;
        else {
            messageToFieldApply.arguments = Arrays.copyOf(allocationExpression.arguments, allocationExpression.arguments.length);
            for (int iArg=0; iArg<messageToFieldApply.arguments.length; iArg++){
                Expression arg = messageToFieldApply.arguments[iArg];
                TypeBinding targetParamType = allocationExpression.binding.parameters[iArg];
                ensureImplicitConversion(arg, targetParamType);
            }
        }

        if (valueRequired)
            messageToFieldApply.valueCast = allocationExpression.resolvedType;

        return messageToFieldApply;
    }

    static void addImplicitBoxingIfNeeded(Expression expression, TypeBinding targetType) {
        if (expression.resolvedType instanceof BaseTypeBinding) //Function.apply always takes boxed types
            addImplicitBoxing(expression, targetType);
    }

    /**
     *
     * @param expression to set boxing request in its implicitConversion
     * @param targetType to use to determine type of conversion
     */
    static void addImplicitBoxing(Expression expression, TypeBinding targetType) {

        expression.implicitConversion &= (-1 << 8 | 0xf); //clear conversion target type, ...FFFF0F

        int targetTypeId;
        if (targetType instanceof BaseTypeBinding)
            targetTypeId = targetType.id; //originally a conversion, we will add boxing
        else if ((expression.implicitConversion & 0xf) != 0)
            targetTypeId = expression.implicitConversion & 0xf; //boxing to make lambda argument
        else
            targetTypeId = targetType.id;

        expression.implicitConversion |=
                (TypeIds.BOXING | (targetTypeId << 4));

    }
    static void addImplicitUnBoxing(Expression expression, TypeBinding targetType) {
        expression.implicitConversion |=
                (TypeIds.UNBOXING | targetType.id);
    }

    /**
     * if an explicit conversion char->int is specified for arg, remove it
     * @param arg
     */
    static void removeCharToIntImplicitConversionIfNeeded(Expression arg) {
        TypeBinding argType = arg.resolvedType;
        if (!(argType instanceof BaseTypeBinding))
            return;
        if (argType.id == TypeIds.T_char) {
            //somehow there can be an implicit conversion char->int in original call. This will not box to Character!
            int targetType = (arg.implicitConversion & IMPLICIT_CONVERSION_MASK) >> 4;
            if (targetType == TypeIds.T_int) {
                arg.implicitConversion ^= (TypeIds.T_int  << 4);
                arg.implicitConversion |= (TypeIds.T_char << 4);
            }
        }
    }

    static QualifiedNameReference makeQualifiedNameReference(String targetFieldNameInThis) {
        char[][] path = new char[1][];
        path[0] = targetFieldNameInThis.toCharArray();

        return new QualifiedNameReference(path, new long[path.length], 0, 0);
    }
    static QualifiedNameReference makeQualifiedNameReference(String thisClassName, String targetFieldNameInThis) {
        char[][] path = new char[2][];
        path[0] = thisClassName.toCharArray();
        path[1] = targetFieldNameInThis.toCharArray();

        return new QualifiedNameReference(path, new long[path.length], 0, 0);
    }
    static SingleNameReference makeSingleNameReference(String targetFieldNameInThis) {
        return new SingleNameReference(targetFieldNameInThis.toCharArray(), 0);
    }

    public static void registerCallToRedirectIfNeeded(MessageSend messageSend, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (
//                    !classReferenceContext.isTestabilityRedirectorField(this.receiver) &&
                !fromTestabilityFieldInitializer(scope) && //it calls original code
//                    !classReferenceContext.isTestabilityRedirectorMethod(scope) &&
                !isTestabilityFieldAccess(messageSend.receiver) &&
                !isLabelledAsDontRedirect(scope.methodScope(), messageSend)
                ) //it calls the testability field apply method

        {
            boolean isInStaticScope = (scope instanceof MethodScope)? ((MethodScope) scope).isStatic : false;
            classReferenceContext.allCallsToRedirect.add(
                    new AbstractMap.SimpleEntry<>(messageSend, isInStaticScope));
        }
    }
    public static void registerCallToRedirectIfNeeded(AllocationExpression allocationExpression, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.classScope().referenceContext;
        if (!fromTestabilityFieldInitializer(scope) &&
                !isLabelledAsDontRedirect(scope.methodScope(), allocationExpression)
           ) {//it calls original code
            boolean isInStaticScope = (scope instanceof MethodScope)? ((MethodScope) scope).isStatic : false;
            classReferenceContext.allCallsToRedirect.add(new AbstractMap.SimpleEntry<>(allocationExpression, isInStaticScope));
        }
    }

    public static List<FieldDeclaration> makeTestabilityFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            LookupEnvironment lookupEnvironment,
            Consumer<Map<Expression, FieldDeclaration>> expressionToRedirectorField) {

        ArrayList<FieldDeclaration> ret = new ArrayList<>();

        Set<InstrumentationOptions> instrumentationOptions = getInstrumentationOptions(lookupEnvironment);

        if (instrumentationOptions.contains(InstrumentationOptions.INSERT_LISTENERS) &&
                !new String(typeDeclaration.name).startsWith("Function")) { //TODO better check for FunctionN

            ret.addAll(makeTestabilityListenerFields(typeDeclaration, referenceBinding));
        }

        if (instrumentationOptions.contains(InstrumentationOptions.INSERT_REDIRECTORS)) {

            try {
                List<FieldDeclaration> redirectorFields = makeTestabilityRedirectorFields(
                        typeDeclaration,
                        referenceBinding,
                        expressionToRedirectorField);
                ret.addAll(redirectorFields);
            } catch (Exception e) {
                throw new RuntimeException(e); //TODO optionally log and continue
            }
        }

        lookupEnvironment.setStepResolveTestabilityFields();

        return ret.stream().
                filter(Objects::nonNull).
                peek(fieldDeclaration -> {
                    System.out.println("injected field: " + fieldDeclaration);
                }).
                peek(fieldDeclaration -> {
                    fieldDeclaration.resolve(typeDeclaration.initializerScope);
                }).
                collect(toList());

    }

    static RuntimeException exceptionVisitorInterrupted = new RuntimeException();

    public static boolean codeContainsTestabilityFieldAccessExpression(CompilationUnitDeclaration unitDeclaration){

        ArrayList<Object> calls = new ArrayList<>();

        Consumer<Expression> checkIsFieldAccessExpression = (Expression ex) -> {

            if (calls.isEmpty() && Testability.isTestabilityFieldAccess(ex)) {
                calls.add(ex);
                System.out.println("found " + ex);
                throw exceptionVisitorInterrupted;
            }
        };

        try {
            unitDeclaration.traverse(new ASTVisitor() {

                @Override
                public boolean visit(MessageSend messageSend, BlockScope scope) {
                    System.out.println("visiting " + messageSend);
                    checkIsFieldAccessExpression.accept(messageSend);
                    return super.visit(messageSend, scope);
                }
                @Override
                public boolean visit(QualifiedNameReference qnr, BlockScope scope) {
                    System.out.println("visiting qnr " + qnr);
                    checkIsFieldAccessExpression.accept(qnr);
                    return super.visit(qnr, scope);
                }
                @Override
                public boolean visit(FieldReference fr, BlockScope scope) {
                    System.out.println("visiting field " + fr);
                    checkIsFieldAccessExpression.accept(fr);
                    return super.visit(fr, scope);
                }
            }, (CompilationUnitScope) null);
        } catch (RuntimeException ex) {
          if (ex!=exceptionVisitorInterrupted) throw ex;
        }

        return !calls.isEmpty();
    }


    //TODO for @NotNull use javax.annotation:javax.annotation-api:1.3.1, check eclipse conflicts
    static Set<InstrumentationOptions> getInstrumentationOptions(/*@NotNull */ ClassScope scope) {
        return scope.compilationUnitScope().environment.instrumentationOptions;
    }
    static Set<InstrumentationOptions> getInstrumentationOptions(LookupEnvironment environment) {
        return environment.instrumentationOptions;
    }

    public static List<FieldDeclaration> makeTestabilityListenerFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding) {

        FieldDeclaration fieldDeclarationPreCreate = makeListenerFieldDeclaration(
                typeDeclaration,
                referenceBinding,
                "$$preCreate");
        FieldDeclaration fieldDeclarationPostCreate = makeListenerFieldDeclaration(
                typeDeclaration,
                referenceBinding,
                "$$postCreate");

        ArrayList<FieldDeclaration> ret = new ArrayList<>();

        ret.add(fieldDeclarationPreCreate);
        ret.add(fieldDeclarationPostCreate);
        return ret;
    }

    /**
     *
     * @param typeDeclaration
     * @param referenceBinding
     * @param originalCallToField
     * @return unique field instances
     */
    public static List<FieldDeclaration> makeTestabilityRedirectorFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            Consumer<Map<Expression, FieldDeclaration>> originalCallToFieldProducer) throws Exception {

        //eliminate duplicates, since multiple call of the same method possible
        Map<String, List<Map.Entry<Expression, Boolean>>> uniqueFieldToExpression = typeDeclaration.allCallsToRedirect.stream().
                collect(
                        Collectors.groupingBy(entry -> {
                            Expression expr = entry.getKey();
                            return testabilityFieldDescriptorUniqueInOverload(expr);
                        })
                );

        List<Map.Entry<Expression, Boolean>> distinctCalls = uniqueFieldToExpression.values().stream().
                map(expressionList -> expressionList.get(0)).
                collect(toList()); //take 1st value of each list (where items have same toUniqueMethodDescriptor()

        List<List<String>> shortNames = distinctCalls.stream().
                map(Map.Entry::getKey).
                map(originalExpression -> testabilityFieldName(originalExpression, true)).
                collect(toList());

        int maxRowSize = shortNames.stream().
                map(List::size).
                max(Comparator.comparingInt(Integer::intValue)).
                orElse(0).
                intValue();

        shortNames = Util.cloneAndEqualizeMatrix(shortNames, maxRowSize, "");

        List<List<String>> longNames = Util.cloneAndEqualizeMatrix(
                distinctCalls.stream().
                map(Map.Entry::getKey).
                map(originalExpression -> testabilityFieldName(originalExpression, false)).
                collect(toList()), maxRowSize, "");

        if (!Util.uniqueMatrix(
                shortNames,
                longNames,
                maxRowSize,
                0,
                IntStream.range(0, shortNames.size()).mapToObj(i->i).collect(toList())
        )){
            throw new Exception("could not make field names unique"); //TODO handle better, maybe assign sequential numbers?
        }

        List<List<String>> uniqueFieldNames = shortNames;

        List<FieldDeclaration> ret = IntStream.range(0, uniqueFieldNames.size()).
                mapToObj(pos -> {
                    Map.Entry<Expression, Boolean> entry = distinctCalls.get(pos);
                    Expression originalCall = entry.getKey();
                    boolean fromStaticContext = entry.getValue();

                    List<String> fieldNameParts = uniqueFieldNames.get(pos);

                    String fieldName = TESTABILITY_FIELD_NAME_PREFIX + fieldNameParts.stream().collect(joining(""));

                    if (fieldName.equals("$$TypeBinding$isLocalType")) { //TODO remove
                        System.out.println("tracking $$TypeBinding$isLocalType");
                    }

                    FieldDeclaration fieldDeclaration = null;

                    if (originalCall instanceof MessageSend) {
                        MessageSend originalMessageSend = (MessageSend) originalCall;

                        fieldDeclaration = makeRedirectorFieldDeclaration(
                                originalMessageSend,
                                typeDeclaration,
                                referenceBinding,
                                fieldName,
                                fromStaticContext);
                    } else if (originalCall instanceof AllocationExpression) {
                        AllocationExpression originalAllocationExpression = (AllocationExpression) originalCall;

                        fieldDeclaration = makeRedirectorFieldDeclaration(
                                originalAllocationExpression,
                                typeDeclaration,
                                referenceBinding,
                                fieldName,
                                fromStaticContext);
                    }
                    return fieldDeclaration;
                }).
                collect(toList());

        //ret is in the order of longFieldNameToExpression.values: 1st element of each list was used to make a field
        List<List<Expression>> longFieldNameToExpressionValues = new ArrayList<>(
            uniqueFieldToExpression.values().stream().
                    map(lst -> lst.stream().map(Map.Entry::getKey).collect(toList())).
                    collect(toList())
        );

        IdentityHashMap<Expression, FieldDeclaration> originalCallToField =
                IntStream.range(0, uniqueFieldToExpression.size()).
                        mapToObj(i -> {

                            List<Expression> list = longFieldNameToExpressionValues.get(i);
                            FieldDeclaration field = ret.get(i);
                            return new AbstractMap.SimpleEntry<>(field, list);
                        }).
                        flatMap(e -> { //form stream by individual expression
                            return e.getValue().stream().
                                    map(ex -> new AbstractMap.SimpleEntry<>(e.getKey(), ex));
                        }).
                        collect(toMap(
                                e -> e.getValue(),
                                e -> e.getKey(),
                                (k,v) -> k, //not null
                                () -> new IdentityHashMap<>()
                        ));

        originalCallToFieldProducer.accept(originalCallToField);
        return ret;
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

        boolean returnsVoid = returnsVoid(originalMessageSend);

        int argc = returnsVoid?
                typeArgumentsForFunction.length :
                typeArgumentsForFunction.length - 1;

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
            String fieldName,
            boolean fromStaticContext) {

        TypeBinding fieldTypeBinding = originalMessageSend.binding.returnType;

        boolean returnsVoid = returnsVoid(originalMessageSend);

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        int parameterShift = receiverPrecedesParameters(originalMessageSend) ? 1 : 0;

        char[][] path = {
            "helpers".toCharArray(),
            functionNameForArgs(originalMessageSend, originalMessageSend.arguments).toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException("testablejava internal error, " + Arrays.stream(path).map(String::new).collect(joining(".")) + " not found");
        }

        Expression[] originalArguments = originalMessageSend.arguments;
        if (originalArguments == null)
            originalArguments = new Expression[0];

        int typeArgsCount = parameterShift + originalArguments.length + (returnsVoid ? 0 : 1);

        TypeBinding[] typeArgumentsForFunction //(receiver), args, return
                   = new TypeBinding[typeArgsCount];

        if (parameterShift > 0)
            typeArgumentsForFunction[0] = //originalMessageSend.binding.declaringClass;
                    originalMessageSend.receiver.resolvedType; //apparent compile-time (non-virtual) class

        int iArg = parameterShift;
        for (TypeBinding arg : originalMessageSend.binding.parameters) {
            typeArgumentsForFunction[iArg++] = boxIfApplicable(arg, lookupEnvironment);
        }

        if (!returnsVoid)
            typeArgumentsForFunction[iArg++] = boxIfApplicable(fieldTypeBinding, lookupEnvironment);

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

        fieldDeclaration.modifiers = ClassFileConstants.AccPublic;

        if (fromStaticContext)
            fieldDeclaration.modifiers |= ClassFileConstants.AccStatic;

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBindingForFunction,
                fieldDeclaration.modifiers,
                typeDeclaration.binding);

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
            //TODO warning: receiver of original message is modified!! clone?
        }

        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = null;//this is to resolve expression without apparent receiver

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        int methodArgCount = typeArgumentsForFunction.length - (returnsVoid ? 0 : 1) - parameterShift;

        Expression[] argv = new SingleNameReference[methodArgCount];
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + parameterShift)).toCharArray();

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);
            argv[i] = singleNameReference;
        }

        if (argv.length != 0)
            messageSendInLambdaBody.arguments = argv; //otherwise stays 0, resolution logic depends on it

        //TODO needed?
        if (originalMessageSend.resolvedType instanceof BaseTypeBinding) //primitive type needs to be boxed when returned from lambda
            addImplicitBoxing(messageSendInLambdaBody, originalMessageSend.resolvedType);

        Block block = new Block(2);
        LabeledStatement labeledStatement = new LabeledStatement(
                TESTABILITYLABEL.toCharArray(),
                new EmptyStatement(0, 0),
                0, 0);

        if (returnsVoid) {
            ReturnStatement returnStatement = new ReturnStatement(null, 0, 0, true);

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



    static FieldDeclaration makeRedirectorFieldDeclaration(
            AllocationExpression originalMessageSend,
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            String fieldName,
            boolean fromStaticContext) {

        TypeBinding fieldTypeBinding =
                originalMessageSend.binding.declaringClass;

        if (new String(fieldTypeBinding.shortReadableName()).equals("void")) {
            TypeBinding tvoid = new SingleTypeReference("Void".toCharArray(), -1).resolveType(referenceBinding.scope);
            fieldTypeBinding = tvoid;
        }

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        char[][] path = {
            "helpers".toCharArray(),
            functionNameForArgs(originalMessageSend.arguments, 0, false).toCharArray()
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

        fieldDeclaration.modifiers = ClassFileConstants.AccPublic;

        if (fromStaticContext)
            fieldDeclaration.modifiers |= ClassFileConstants.AccStatic;


        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBinding,
                fieldDeclaration.modifiers,
                typeDeclaration.binding);

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
        messageSendInLambdaBody.binding = originalMessageSend.binding;//TODO null to match MessageSend version? see "fixed static import case' commit

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

    static boolean returnsVoid(MessageSend messageSend) {
        return messageSend.binding.returnType instanceof VoidTypeBinding &&
                !messageSend.binding.isConstructor(); //somehow constructors have void return type
    }

    static boolean receiverPrecedesParameters(MessageSend messageSend) {

        if (messageSend.binding.isStatic() || messageSend.receiver instanceof ThisReference) //receiver is hardcoded in lambda implementation
            return false;
        return true;
    }
    static FieldDeclaration makeListenerFieldDeclaration(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        char[][] path = {
                "java".toCharArray(),
                "util".toCharArray(),
                "function".toCharArray(),
                "Consumer".toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException("testablejava internal error, " + new String(path[0]) + " not found");
        }

        TypeBinding[] typeArguments //class
                = {typeDeclaration.binding};

        ParameterizedTypeBinding typeBinding =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArguments,
                        referenceBinding);

        TypeReference[][] typeReferences = new TypeReference[path.length][];
        typeReferences[path.length - 1] = new TypeReference[]{Testability.typeReferenceFromTypeBinding(Testability.boxIfApplicable(typeDeclaration.binding, lookupEnvironment))};

        ParameterizedQualifiedTypeReference parameterizedQualifiedTypeReference = new ParameterizedQualifiedTypeReference(
                path,
                typeReferences,
                0,
                new long[path.length]);

        fieldDeclaration.type = parameterizedQualifiedTypeReference;

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBinding,
                fieldDeclaration.modifiers | ClassFileConstants.AccStatic | ClassFileConstants.AccPublic,
                typeDeclaration.binding);

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

        LambdaExpression lambdaExpression = new LambdaExpression(typeDeclaration.compilationResult, false);

        Argument[] arguments = new Argument[1]; //TODO use this method in other make..FieldDeclaration
        TypeReference typeReference =
                Testability.typeReferenceFromTypeBinding(
                        Testability.boxIfApplicable(typeDeclaration.binding, lookupEnvironment));

        arguments[0] = new Argument((" arg" + 0).toCharArray(), 0, typeReference, 0);

        lambdaExpression.setArguments(arguments);

        lambdaExpression.setExpressionContext(ExpressionContext.INVOCATION_CONTEXT);//originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(fieldBinding.type);

        Block block = new Block(0);

        ReturnStatement returnStatement = new ReturnStatement(null, 0, 0, true);
        //note: null expression has special treatment in code gen, will generate return with no arg
        block.statements = new Statement[]{
            returnStatement
        };

        lambdaExpression.setBody(block);

        fieldDeclaration.initialization = lambdaExpression;

        return fieldDeclaration;
    }

    static FieldDeclaration makeSampleFieldDeclaration(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        char[][] path = {
                "java".toCharArray(),
                "lang".toCharArray(),
                "String".toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException("testablejava internal error, " + new String(path[0]) + " not found");
        }

        TypeBinding[] typeArguments //class
                = {};

        TypeReference[][] typeReferences = new TypeReference[path.length][];
        typeReferences[path.length - 1] = new TypeReference[]{Testability.typeReferenceFromTypeBinding(Testability.boxIfApplicable(typeDeclaration.binding, lookupEnvironment))};

        QualifiedTypeReference fieldTypeReference = new QualifiedTypeReference(
                path,
                new long[path.length]);

        fieldTypeReference.toString();

        fieldDeclaration.type = fieldTypeReference; //parameterizedQualifiedTypeReference;

        TypeBinding typeBinding =
                lookupEnvironment.askForType(path);

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBinding,
                fieldDeclaration.modifiers | ClassFileConstants.AccStatic | ClassFileConstants.AccPublic  /*| ExtraCompilerModifiers.AccUnresolved*/,
                typeDeclaration.binding);//sourceType);

        fieldDeclaration.binding = fieldBinding;
//        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

        fieldDeclaration.initialization = new NullLiteral(0,0);
        return fieldDeclaration;
    }

    /**
     * Function name reflects # of its arguments, which is # of type args - 1
     * @param messageSend
     * @return
     */
    static String functionNameForArgs(MessageSend messageSend, Expression [] messageSendArguments) {
        int parameterShift = receiverPrecedesParameters(messageSend) ? 1 : 0;
        boolean returnsVoid = returnsVoid(messageSend);
        return functionNameForArgs(messageSendArguments, parameterShift, returnsVoid);
    }

    static String functionNameForArgs(Expression[] arguments, int parameterShift, boolean returnsVoid) {
        int functionArgCount = (arguments == null? 0 : arguments.length) + parameterShift;

        String name = (
                returnsVoid ?
                        "Consumer" :
                        "Function"
        ) + functionArgCount;

        return name;
    }

    static public TypeReference typeReferenceFromTypeBinding(TypeBinding typeBinding) {
        int dim = typeBinding.dimensions();
        if (dim == 0){
            if (typeBinding instanceof ReferenceBinding) {
                ReferenceBinding binaryTypeBinding = (ReferenceBinding) typeBinding;
                if (typeBinding instanceof WildcardBinding) {
                    Wildcard wildcard = new Wildcard(((WildcardBinding) typeBinding).boundKind);
                    wildcard.bound = typeReferenceFromTypeBinding(((WildcardBinding) typeBinding).bound);
                    return wildcard;
                }
                if (!typeBinding.isParameterizedType()) {
                    return new QualifiedTypeReference(binaryTypeBinding.compoundName, new long[binaryTypeBinding.compoundName.length]);
                } else {
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;
                    TypeReference[] typeArguments = new TypeReference[parameterizedTypeBinding.arguments.length];

                    Arrays.stream(parameterizedTypeBinding.arguments).
                            map(Testability::typeReferenceFromTypeBinding).
                            collect(Collectors.toList()).toArray(typeArguments);

                    TypeReference[][] typeArgumentsCompound = new TypeReference[binaryTypeBinding.compoundName.length][];//{typeArguments};
                    typeArgumentsCompound[typeArgumentsCompound.length - 1] = typeArguments;
                    return new ParameterizedQualifiedTypeReference(
                            binaryTypeBinding.compoundName,
                            typeArgumentsCompound,
                            dim,
                            new long[binaryTypeBinding.compoundName.length]
                    );
                }
            } else if (typeBinding instanceof BaseTypeBinding){
                return TypeReference.baseTypeReference(typeBinding.id, 0);
            } else { //TODO will this ever happen?
                if (!typeBinding.isParameterizedType()) {
                    return new SingleTypeReference(
                            typeBinding.sourceName(), 0
                    );
                } else {
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;

                    TypeReference[] typeArguments = new TypeReference[parameterizedTypeBinding.arguments.length];

                    Arrays.stream(parameterizedTypeBinding.arguments).
                            map(Testability::typeReferenceFromTypeBinding).
                            collect(Collectors.toList()).toArray(typeArguments);

                    return new ParameterizedSingleTypeReference(
                            typeBinding.sourceName(),
                            typeArguments,
                            dim,
                            0
                    );
                }
            }

        } else { //array
            TypeReference typeReference = typeReferenceFromTypeBinding(typeBinding.leafComponentType());
            char[][] typeName = typeReference.getTypeName();
            if (typeBinding.leafComponentType() instanceof BaseTypeBinding) {
                //array of primitive type
                return new ArrayTypeReference(
                        typeBinding.leafComponentType().sourceName(),
                        dim, //- 1,
                        0
                );
            }

            long[] poss = new long[typeName.length];
            poss[poss.length - 1] = typeBinding.id;

            return new ArrayQualifiedTypeReference(
                    typeName, //typeBinding.leafComponentType().compoundName//.sourceName(),//typeBinding.sourceName(),
                    dim, //- 1,
                    poss
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

    static public List<String> testabilityFieldName(Expression originalCall, boolean shortClassName) {
        if (!(originalCall instanceof Invocation))
            throw new RuntimeException("domain error on argument, must be instance of Invocation");

        MethodBinding binding = ((Invocation) originalCall).binding();


        List<String> ret = new ArrayList<>();



        if (originalCall instanceof MessageSend) {
            MessageSend originalMessageSend = (MessageSend) originalCall;
            TypeBinding receiverBinding = originalMessageSend.receiver.resolvedType;

            String invokedClassName;

            if (receiverBinding instanceof ReferenceBinding)
                invokedClassName = new String(readableName((ReferenceBinding) receiverBinding /*binding.declaringClass*/, shortClassName));
            else
                invokedClassName = new String(readableName(binding.declaringClass, shortClassName)); //TODO is this reachable?

            String invokedMethodName = new String(originalMessageSend.selector);

            ret.addAll(testabilityFieldNameForExternalAccess(invokedClassName, invokedMethodName));

            List<String> argTypes = Arrays.stream(originalMessageSend.argumentTypes).
                    map(argType -> escapeArgType(argType, shortClassName)).
                    collect(toList());

            if (!argTypes.isEmpty()) ret.add(TESTABILITY_ARG_LIST_SEPARATOR);

            ret.add(argTypes.stream().collect(joining("$")));

            return ret;
        }
        else if (originalCall instanceof AllocationExpression) {

            String invokedClassName = new String(readableName(binding.declaringClass, shortClassName));

            ret.addAll(testabilityFieldNameForNewOperator(invokedClassName));

            List<String> argTypes = Arrays.stream(((AllocationExpression) originalCall).argumentTypes).
                    map(argType -> escapeArgType(argType, shortClassName)).
                    collect(toList());

            if (!argTypes.isEmpty()) ret.add(TESTABILITY_ARG_LIST_SEPARATOR);

            ret.addAll(argTypes);

            return ret;
        }
        else
            throw new RuntimeException("domain error on argument");
    }

    static String escapeArgType(TypeBinding argType, boolean shortForm) {
        String constPoolName = new String(argType.constantPoolName());
        if (shortForm) {
            String shortName = Util.lastChunk(constPoolName, "/");
            if (constPoolName.startsWith("[") && !shortName.startsWith("["))
                shortName = "[" + shortName; //preserve, since it is not in last chunk
            String shortEscapedName = escapePathSeparatorsAndArrayInTypeName(shortName) ;
            return escapeTypeArgsInTypeName(shortEscapedName);
        } else {
            String longEscapedName = escapePathSeparatorsAndArrayInTypeName(constPoolName);
            return escapeTypeArgsInTypeName(longEscapedName);
        }
    }

    static String escapePathSeparatorsAndArrayInTypeName(String name) {
        return name.
                replace('/','$').
                replace('[', '\u24b6').//array symbol Ⓐ
                replace(';',' ').trim();
    }

    static String escapeTypeArgsInTypeName(String className) {
        return className.
                replace("<","_").
                replace(">","_").
                replace('.','$');
    }

    /**
     * make a uniqe string from field name and arg types
     * @param originalCall
     * @return
     */
    static public String testabilityFieldDescriptorUniqueInOverload(Expression originalCall) {
        if (!(originalCall instanceof Invocation))
            throw new RuntimeException("domain error on argument, must be instance of Invocation");

        MethodBinding binding = ((Invocation) originalCall).binding();
        return testabilityFieldName(originalCall, false) + new String(binding.signature());
    }

    static char [] readableName(ReferenceBinding binding, boolean shortClassName) { //see ReferenceBinding::readableName
        StringBuffer nameBuffer = new StringBuffer(10);

        if (shortClassName)
            nameBuffer.append(binding.sourceName);
        else {
            if (binding.isMemberType()) {
                nameBuffer.append(CharOperation.concat(binding.enclosingType().readableName(), binding.sourceName, '.'));
            } else {
                char[][] compoundName;
                if (binding instanceof ParameterizedTypeBinding) {
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) binding;

                    compoundName = parameterizedTypeBinding.actualType().compoundName;

                } else {
                    compoundName = binding.compoundName;
                }

                nameBuffer.append(CharOperation.concatWith(compoundName, '.'));
            }
        }
        if (!shortClassName && binding instanceof ParameterizedTypeBinding) {
            TypeBinding[] arguments = ((ParameterizedTypeBinding) binding).arguments;
            if (arguments != null &&
                    arguments.length > 0) { // empty arguments array happens when PTB has been created just to capture type annotations
                nameBuffer.append('_');
                for (int i = 0, length = arguments.length; i < length; i++) {
                    if (i > 0) nameBuffer.append('_');
                    nameBuffer.append(arguments[i].readableName()); //TODO several TypeBinding subclass implementations
                }
                nameBuffer.append('_');
            }
        }
        int nameLength = nameBuffer.length();
        char[] readableName = new char[nameLength];
        nameBuffer.getChars(0, nameLength, readableName, 0);
        return readableName;
    }

    static public List<String> testabilityFieldNameForExternalAccess(String methodClassName, String calledMethodName) {
        List<String> ret = new ArrayList<>();
        ret.add(escapeTypeArgsInTypeName(methodClassName));
        ret.add("$" + calledMethodName);
        return ret;
    }

    static public List<String> testabilityFieldNameForNewOperator(String className) {
        List<String> ret = new ArrayList<>();
        ret.add(className.replace('.','$'));
        ret.add("$new");
        return ret;
    }

    static public boolean isTestabilityRedirectorFieldName(String fieldName) {
        return fieldName.startsWith(TESTABILITY_FIELD_NAME_PREFIX);
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
        String fieldName = "";
        if (receiver instanceof FieldReference) {

            FieldReference fieldReference = (FieldReference) receiver;
            fieldName = new String(fieldReference.token);
        } else if (receiver instanceof MessageSend) { //e.g. when it is not known yet to be field access
            MessageSend messageSend = (MessageSend) receiver;
            isTestabilityFieldAccess(messageSend.receiver);
        } else if (receiver instanceof QualifiedNameReference){
            char[][] tokens = ((QualifiedNameReference) receiver).tokens;
            fieldName = tokens.length>0? new String(tokens[tokens.length - 1]) : "";
        }
        return isTestabilityRedirectorFieldName(fieldName);
    }

    /**
     * modify its statements in place: insert static calls to listeners before and after
     * @param constructorDeclaration
     * @param typeDeclaration
     */
    public static void addListenerCallsToConstructor(
            ConstructorDeclaration constructorDeclaration,
            TypeDeclaration typeDeclaration) {

        Set<InstrumentationOptions> instrumentationOptions = getInstrumentationOptions(constructorDeclaration.scope.classScope());

        if (!instrumentationOptions.contains(InstrumentationOptions.INSERT_LISTENERS))
            return;

        if (constructorDeclaration.statements == null)
            constructorDeclaration.statements = new Statement[]{};

        int originalStatementsCount = constructorDeclaration.statements.length;

        Statement [] newStatements = Arrays.copyOf(constructorDeclaration.statements, originalStatementsCount +2);
        System.arraycopy(constructorDeclaration.statements, 0, newStatements, 1, originalStatementsCount);

        newStatements[0] = statementForListenerCall(
                constructorDeclaration,
                typeDeclaration,
                "accept",
                "$$preCreate");

        newStatements[newStatements.length - 1] = statementForListenerCall(
                constructorDeclaration,
                typeDeclaration,
                "accept",
                "$$postCreate");

        constructorDeclaration.statements = newStatements;

    }

    static Statement statementForListenerCall(
            ConstructorDeclaration constructorDeclaration,
            TypeDeclaration typeDeclaration,
            String methodNameInListenerField,
            String targetFieldNameInThis) {

        MessageSend messageToFieldApply = new MessageSend();

        messageToFieldApply.selector = methodNameInListenerField.toCharArray();

        NameReference fieldNameReference = makeQualifiedNameReference(
                new String(typeDeclaration.name),
                targetFieldNameInThis);

        messageToFieldApply.receiver = fieldNameReference;

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        messageToFieldApply.arguments = new Expression[]{new ThisReference(0,0)};

        LabeledStatement labeledStatement = new LabeledStatement(
                (DONTREDIRECT + "top" + System.nanoTime()).toCharArray(),
                messageToFieldApply, 0, 0);

        labeledStatement.targetLabel = new BranchLabel(); //normally done in analyseCode
        labeledStatement.resolve(constructorDeclaration.scope);

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException("internal error: unresolved field " + fieldNameReference);

        return labeledStatement;
    }


}
