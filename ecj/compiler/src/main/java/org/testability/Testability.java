package org.testability;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.InstrumentationOptions;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.BranchLabel;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.flow.InitializationFlowContext;
import org.eclipse.jdt.internal.compiler.flow.UnconditionalFlowInfo;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.problem.AbortType;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    public static final String TESTABLEJAVA_INTERNAL_ERROR = "testablejava internal error";

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
        TypeDeclaration classDeclaration = methodScope.outerMostClassScope().referenceContext;

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
        } else if (methodScope.referenceContext instanceof TypeDeclaration) {
            TypeDeclaration declaration = (TypeDeclaration) methodScope.referenceContext;

            declaration.traverse(new ASTVisitor() {
                @Override
                public void endVisit(LabeledStatement labeledStatement, BlockScope scope) {
                    if (new String(labeledStatement.label).startsWith(DONTREDIRECT))
                        labelledStatementsDontRedirect.add(labeledStatement);
                    super.endVisit(labeledStatement, scope);
                }

            }, methodScope.classScope());
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

                @Override
                public void endVisit(QualifiedAllocationExpression allocationExpression, BlockScope scope) {
                    if (allocationExpression == expressionToBeReplaced)
                        found.set(true);
                    super.endVisit(allocationExpression, scope);
                }
            }, methodScope);
        }

        return found.get();
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

        TypeDeclaration typeDeclaration = currentScope.outerMostClassScope().referenceContext;
        FieldDeclaration redirectorFieldDeclaration = typeDeclaration.callExpressionToRedirectorField.get(messageSend);

        MessageSend messageToFieldApply = new MessageSend();

        messageToFieldApply.implicitConversion = messageSend.implicitConversion; //TODO experiment

        String targetFieldNameInThis = new String(redirectorFieldDeclaration.name);//testabilityFieldNameForExternalAccess(methodClassName, messageSend.selector);

        String selector = returnsVoid(messageSend)?
                        TARGET_REDIRECTED_METHOD_NAME_FOR_CONSUMER :
                        TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION;

        messageToFieldApply.selector = selector.toCharArray();

        QualifiedNameReference qualifiedNameReference = makeQualifiedNameReference(targetFieldNameInThis);
        qualifiedNameReference.resolve(currentScope);

        if (qualifiedNameReference.resolvedType == null) {
            currentScope.problemReporter().testabilityInstrumentationError(
                    currentScope.referenceContext().compilationResult().toString() + " during code replace");
            return null;
        }

        messageToFieldApply.receiver = qualifiedNameReference;

        //use the field's binding directly
        messageToFieldApply.binding = //redirectorFieldDeclaration.type.resolvedType.getMethods(selector.toCharArray())[0];
                redirectorFieldDeclaration.binding.type.getMethods(selector.toCharArray())[0];

        if (null == messageToFieldApply.receiver.resolvedType) {
            currentScope.problemReporter().testabilityInstrumentationError(TESTABLEJAVA_INTERNAL_ERROR + ": unresolved field " + qualifiedNameReference);
            return null;
        }

        messageToFieldApply.argumentTypes = messageToFieldApply.binding.parameters;//TODO original message has this, needed?

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        int originalArgCount = messageSend.arguments == null ? 0 : messageSend.arguments.length;
        Expression[] argsWithReceiver = new Expression[1 + originalArgCount];

        int iArg = 0;
        AllocationExpression callSiteExpression;

        try {
            callSiteExpression = makeCallSiteExpression(messageSend, currentScope);
        } catch (Exception ex) {
            testabilityInstrumentationError(currentScope, "makeCallSiteExpression", ex);
            return null;
        }

        argsWithReceiver[iArg++] = callSiteExpression;

        for (int iArgOriginal = 0; iArgOriginal< originalArgCount; iArgOriginal++) {

            Expression arg = messageSend.arguments[iArgOriginal];
            TypeBinding targetParamType =
                    messageSend.argumentTypes[iArgOriginal];

            ensureImplicitConversion(arg, targetParamType);

            argsWithReceiver[iArg++] = arg;
        }

        //construct call site creation expression

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

        if (!diagnoseBinding(messageToFieldApply, currentScope.methodScope(), redirectorFieldDeclaration))
            return null;

        return messageToFieldApply;
    }

    static AllocationExpression makeCallSiteExpression(MessageSend messageSend, BlockScope currentScope) {

        LookupEnvironment lookupEnvironment = currentScope.environment();

        AllocationExpression allocationExpression = new AllocationExpression();

        TypeBinding callingTypeBinding = convertIfAnonymous(currentScope.classScope().referenceContext.binding);

        callingTypeBinding = convertToLocalIfGeneric(callingTypeBinding, lookupEnvironment);

        TypeBinding calledTypeBinding = messageSend.actualReceiverType;
        calledTypeBinding = convertToLocalIfGeneric(calledTypeBinding, lookupEnvironment);

        ReferenceBinding callSiteTypeBinging = bindingForCallContextType(
                callingTypeBinding,
                calledTypeBinding,
                currentScope.environment()
        );

        TypeReference callSiteType = typeReferenceFromTypeBinding(callSiteTypeBinging);

        allocationExpression.type = callSiteType;

        Expression exprGetCallingClass = new StringLiteral(removeLocalPrefix(callingTypeBinding.readableName()), 0,0,0);

        Expression exprGetCalledClass = new StringLiteral(removeLocalPrefix(calledTypeBinding.readableName()), 0,0,0);

        TypeReference callingTypeReference = typeReferenceFromTypeBinding(callingTypeBinding);

        TypeReference calledTypeReference = typeReferenceFromTypeBinding(calledTypeBinding);

        Expression exprGetCallingClassInstance = currentScope.methodScope().isStatic?
                new CastExpression(new NullLiteral(0,0), callingTypeReference) :
                new ThisReference(0, 0);//new QualifiedThisReference(callingTypeReference,0,0);

        Expression exprGetCalledClassInstance = messageSend.binding.isStatic()?
                new CastExpression(new NullLiteral(0,0), calledTypeReference):
                (messageSend.receiver instanceof ThisReference && messageSend.receiver.resolvedType.id !=
                        calledTypeBinding.id)?
                        new QualifiedThisReference(calledTypeReference,0,0) : messageSend.receiver;
        //(forcing Qualified, e.g. X.this.fn() for inner types since simple fn() call will result in ThisReference poimnitng to inner class and MessageSend magically fixes this
        //in its actualReceiverType)

        Expression[] argv = {
                exprGetCallingClass,
                exprGetCalledClass,
                exprGetCallingClassInstance,
                exprGetCalledClassInstance
        };
        //TODO apply on other makeCallSiteExpression

        allocationExpression.arguments = argv;

        allocationExpression.resolve(currentScope);
        return allocationExpression;
    }
    static AllocationExpression makeCallSiteExpression(AllocationExpression messageSend, BlockScope currentScope) {
        LookupEnvironment lookupEnvironment = currentScope.environment();

        AllocationExpression allocationExpression = new AllocationExpression();

        TypeBinding callingTypeBinding = convertIfAnonymous(currentScope.classScope().referenceContext.binding);

        callingTypeBinding = convertToLocalIfGeneric(callingTypeBinding, lookupEnvironment);

        TypeBinding calledTypeBinding = messageSend.type.resolvedType;//actualReceiverType;

        calledTypeBinding = convertToLocalIfGeneric(calledTypeBinding, lookupEnvironment);

        TypeReference callingTypeReference = typeReferenceFromTypeBinding(callingTypeBinding);

        TypeReference calledTypeReference = typeReferenceFromTypeBinding(calledTypeBinding);

        ReferenceBinding callSiteTypeBinging = bindingForCallContextType(
                callingTypeBinding,
                calledTypeBinding,
                currentScope.environment()
        );

        TypeReference callSiteType = typeReferenceFromTypeBinding(callSiteTypeBinging);

        allocationExpression.type = callSiteType;

        Expression exprGetCallingClass = new StringLiteral(removeLocalPrefix(callingTypeBinding.readableName()), 0,0,0);

        Expression exprGetCalledClass = new StringLiteral(removeLocalPrefix(calledTypeBinding.readableName()), 0,0,0);

        Expression exprGetCallingClassInstance = currentScope.methodScope().isStatic?
                new CastExpression(new NullLiteral(0,0), callingTypeReference) :
                new ThisReference(0,0);//new QualifiedThisReference(callingTypeReference,0,0);

        Expression exprGetCalledClassInstance = //no instance yet
                new CastExpression(new NullLiteral(0,0), calledTypeReference);

        Expression[] argv = {
                exprGetCallingClass,
                exprGetCalledClass,
                exprGetCallingClassInstance,
                exprGetCalledClassInstance
        };

        allocationExpression.arguments = argv;

        allocationExpression.resolve(currentScope);

        return allocationExpression;
    }

    static void ensureImplicitConversion(Expression arg, TypeBinding targetParamType) {
        removeCharToIntImplicitConversionIfNeeded(arg);

        addImplicitBoxingIfNeeded(arg, arg.resolvedType.equals(targetParamType)? arg.resolvedType : targetParamType);
    }

    static boolean diagnoseBinding(MessageSend messageSend, MethodScope methodScope, FieldDeclaration redirectorFieldDeclaration) {
        MethodBinding binding = messageSend.binding;
        if (binding instanceof ProblemMethodBinding) {

            String message = String.format("when compiling class %s method %s: testability field %s: method not found in field class %s binding: %s; closest match: ",
                    new String(methodScope.classScope().referenceContext.name),
                    new String(((MethodDeclaration) methodScope.referenceContext).selector),
                    new String(redirectorFieldDeclaration.name),
                    new String(messageSend.receiver.resolvedType.readableName()),
                    binding,
                    ((ProblemMethodBinding) binding).closestMatch);

            methodScope.problemReporter().testabilityInstrumentationError(message);

            return false;
        }
        return true;
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

        TypeDeclaration typeDeclaration = currentScope.outerMostClassScope().referenceContext;
        FieldDeclaration redirectorFieldDeclaration = typeDeclaration.callExpressionToRedirectorField.get(allocationExpression);

        MessageSend messageToFieldApply = new MessageSend();

        String targetFieldNameInThis = new String(redirectorFieldDeclaration.name);

        messageToFieldApply.selector = TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION.toCharArray(); //always Function for allocation

        NameReference fieldNameReference = makeSingleNameReference(targetFieldNameInThis);

        { //prevent diagnostic of invisible fields: Pb(75) Cannot reference a field before it is defined; useless here
            int savId = currentScope.methodScope().lastVisibleFieldID;
            currentScope.methodScope().lastVisibleFieldID = -1;

            fieldNameReference.resolve(currentScope);

            currentScope.methodScope().lastVisibleFieldID = savId;
        }

        if (fieldNameReference.resolvedType == null) {
            currentScope.problemReporter().testabilityInstrumentationError(
                    currentScope.referenceContext().compilationResult().toString() + " during code replace");
            return null;
        }

        messageToFieldApply.receiver = fieldNameReference;

        messageToFieldApply.binding = redirectorFieldDeclaration.type.resolvedType.getMethods(messageToFieldApply.selector)[0];

        if (null == messageToFieldApply.receiver.resolvedType) {
            currentScope.problemReporter().testabilityInstrumentationError(
                    TESTABLEJAVA_INTERNAL_ERROR + ": unresolved field " + fieldNameReference);
            return null;
        }

        messageToFieldApply.actualReceiverType = messageToFieldApply.receiver.resolvedType;

        if (allocationExpression.arguments == null)
            messageToFieldApply.arguments = new Expression[1];
        else {
            messageToFieldApply.arguments = new Expression[allocationExpression.arguments.length + 1];

            System.arraycopy(
                    allocationExpression.arguments,
                    0,
                    messageToFieldApply.arguments,
                    1,
                    allocationExpression.arguments.length);
        }
        AllocationExpression callSiteExpression;
        try {
            callSiteExpression = makeCallSiteExpression(allocationExpression, currentScope);
        } catch (Exception ex) {
            testabilityInstrumentationError(currentScope, "makeCallSiteExpression", ex);
            return null;
        }
        messageToFieldApply.arguments[0] = callSiteExpression;

        for (int iArg=1; iArg<messageToFieldApply.arguments.length; iArg++){
            Expression arg = messageToFieldApply.arguments[iArg];
            TypeBinding targetParamType = allocationExpression.argumentTypes[iArg - 1];
            ensureImplicitConversion(arg, targetParamType);
        }

        if (valueRequired)
            messageToFieldApply.valueCast = allocationExpression.resolvedType;

        return messageToFieldApply;
    }

    static public void testabilityInstrumentationError(Scope currentScope, String message, Exception ex) {
        currentScope.problemReporter().testabilityInstrumentationError(
                TESTABLEJAVA_INTERNAL_ERROR + ": " + message,
                ex);
    }
    static public void testabilityInstrumentationError(Scope currentScope, String message) {
        currentScope.problemReporter().testabilityInstrumentationError(
                TESTABLEJAVA_INTERNAL_ERROR + ": " + message);
    }
    static public void testabilityInstrumentationWarning(Scope currentScope, String message) {
        if (currentScope != null)
            currentScope.problemReporter().testabilityInstrumentationWarning(message);
        else
            System.out.println("WARN: " + message);
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
        TypeDeclaration classReferenceContext = scope.outerMostClassScope().referenceContext;

        if (
                !classReferenceContext.methodsResolved &&
//                    !classReferenceContext.isTestabilityRedirectorField(this.receiver) &&
                !fromTestabilityFieldInitializer(scope) && //it calls original code
//                    !classReferenceContext.isTestabilityRedirectorMethod(scope) &&
                !isTestabilityFieldAccess(messageSend.receiver) &&
                !isLabelledAsDontRedirect(scope.methodScope(), messageSend)
                ) //it calls the testability field apply method

        {
            MethodScope methodScope = scope.methodScope();
            TypeDeclaration typeContainingExpression = methodScope.classScope().referenceContext;
            classReferenceContext.allCallsToRedirect.add(
                    new AbstractMap.SimpleEntry<>(messageSend, typeContainingExpression));
        }
    }
    public static void registerCallToRedirectIfNeeded(AllocationExpression allocationExpression, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.outerMostClassScope().referenceContext;

        if (!classReferenceContext.methodsResolved &&
                !fromTestabilityFieldInitializer(scope) &&
                !isLabelledAsDontRedirect(scope.methodScope(), allocationExpression)
           ) {//it calls original code
            MethodScope methodScope = scope.methodScope();

            TypeDeclaration typeContainingExpression = methodScope.classScope().referenceContext;
            classReferenceContext.allCallsToRedirect.add(new AbstractMap.SimpleEntry<>(allocationExpression, typeContainingExpression));
        }
    }
    public static void registerAnonymousType(TypeDeclaration anonymousType, BlockScope scope) {
        TypeDeclaration classReferenceContext = scope.outerMostClassScope().referenceContext;

        MethodScope methodScope = scope.methodScope();

        TypeDeclaration typeContainingExpression = methodScope.classScope().referenceContext;
        classReferenceContext.anonymousTypes.add(new AbstractMap.SimpleEntry<>(anonymousType, typeContainingExpression));
    }


    public static List<FieldDeclaration> makeTestabilityFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            LookupEnvironment lookupEnvironment,
            Consumer<Map<Expression, FieldDeclaration>> expressionToRedirectorField) {

        ArrayList<FieldDeclaration> ret = new ArrayList<>();

        try {

            Set<InstrumentationOptions> instrumentationOptions = getInstrumentationOptions(lookupEnvironment);

            if (instrumentationOptions.contains(InstrumentationOptions.INSERT_LISTENERS) &&
                    !new String(typeDeclaration.name).startsWith("Function")) { //TODO better check for FunctionN

                if (typeDeclaration.binding.outermostEnclosingType() == typeDeclaration.binding) { //processing top-level type
                    //TODO for all instantiated types if typeDeclaration is generic

                    ret.addAll(makeTestabilityListenerFields(typeDeclaration.compilationResult, typeDeclaration.binding, referenceBinding));

                    //find all local type declarations and add corresponding fields
                    List<TypeDeclaration> localTypeDeclarations = findTypeDeclarations(typeDeclaration, null);

                    //note: only instantiated generic types should be included
                    List<ParameterizedTypeBinding> instantiatedTypeDeclarations =
                            getInstantiatedTypeBindings(
                                    localTypeDeclarations.stream().
                                            map(decl -> decl.binding).
                                            map(SourceTypeBinding.class::cast).
                                            collect(toList()),
                                    lookupEnvironment);
                    Stream.concat(
                            localTypeDeclarations.stream().map(t -> t.binding).filter(b -> !isGenericType(b)),
                            instantiatedTypeDeclarations.stream()).
                            forEach(typeBinding -> {
                                ret.addAll(makeTestabilityListenerFields(typeDeclaration.compilationResult, typeBinding, referenceBinding));
                            });

                }

            }

            if (instrumentationOptions.contains(InstrumentationOptions.INSERT_REDIRECTORS)) {

                try {
                    List<FieldDeclaration> redirectorFields = makeTestabilityRedirectorFields(
                            typeDeclaration,
                            referenceBinding,
                            lookupEnvironment,
                            expressionToRedirectorField);
                    ret.addAll(redirectorFields);
                } catch (Exception ex) {
                    testabilityInstrumentationError(typeDeclaration.scope,"a field cannot be created", ex);
                }
            }

            lookupEnvironment.setStepResolveTestabilityFields();

            return ret.stream().
                    filter(Objects::nonNull).
                    peek(fieldDeclaration -> {
                        System.out.println("injected field: " + fieldDeclaration);
                    }).
                    map(fieldDeclaration -> {
                        try {
                            fieldDeclaration.resolve(typeDeclaration.initializerScope);

                            if (!validateMessageSendsInCode(fieldDeclaration, typeDeclaration.initializerScope)) {
                                Testability.testabilityInstrumentationWarning(
                                        typeDeclaration.initializerScope,
                                        "The field cannot be validated, and will not be injected: " + new String(fieldDeclaration.name)
                                );
                                return null;
                            }

                            UnconditionalFlowInfo flowInfo = FlowInfo.initial(0);
                            FlowContext flowContext = null;
                            InitializationFlowContext staticInitializerContext = new InitializationFlowContext(null,
                                    typeDeclaration,
                                    flowInfo,
                                    flowContext,
                                    typeDeclaration.staticInitializerScope);

                            fieldDeclaration.analyseCode(typeDeclaration.staticInitializerScope, staticInitializerContext, flowInfo);

                            return fieldDeclaration;
                        } catch (Exception ex) {
                            testabilityInstrumentationError(
                                    typeDeclaration.scope,
                                    "The field cannot be resolved: " + new String(fieldDeclaration.name),
                                    ex);

                            return null;
                        }
                    }).
                    filter(Objects::nonNull).
                    collect(toList());

        } catch (Exception ex) {
            testabilityInstrumentationError(typeDeclaration.scope,"unexpected", ex);
            return ret;
        }
    }

    /**
     *
     * @param genericTypeBindings
     * @param environment
     * @return all instantiations of the type
     */

    static List<ParameterizedTypeBinding> getInstantiatedTypeBindings(List<ReferenceBinding> genericTypeBindings, LookupEnvironment environment) {
        return genericTypeBindings.stream().
                filter(Testability::isGenericType).
                flatMap(t -> findTypeInstantiations(t, environment).stream()).
                collect(toList());
    }

    /**
     *
     * @param typeBinding
     * @return if it is genetic type (not instance)
     */
    static boolean isGenericType(TypeBinding typeBinding) {
        return !typeBinding.isParameterizedType() &&
                typeBinding.typeVariables() != null && typeBinding.typeVariables().length > 0;
    }

    static List<TypeDeclaration> findTypeDeclarations(TypeDeclaration typeDeclaration, ClassScope classScope) {

        List<TypeDeclaration> ret = new ArrayList<>();

        typeDeclaration.traverse(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration m, BlockScope scope) {
                ret.add(m);
                return true;
            }
        }, classScope);

        return ret;

    }
    static List<ParameterizedTypeBinding> findTypeInstantiations(ReferenceBinding typeBinding, LookupEnvironment environment) {
        TypeBinding[] derivedTypes = environment.getDerivedTypes(typeBinding);

        return Arrays.stream(derivedTypes).
                filter(t -> (t instanceof ParameterizedTypeBinding)).
                map(t -> (ParameterizedTypeBinding)t).
                filter(t -> t.arguments != null).
                filter(t ->
                        !Arrays.stream(t.arguments).anyMatch(a->a instanceof TypeVariableBinding)).//any arg is a type variable, not actual type

                collect(toList());
    }


    static boolean validateMessageSendsInCode(FieldDeclaration fieldDeclaration, MethodScope scope) {

        if (fieldDeclaration.binding == null)
            return false;

        if ((fieldDeclaration.initialization instanceof FunctionalExpression && ((FunctionalExpression) fieldDeclaration.initialization).binding == null))
            return false;
        try {
            fieldDeclaration.traverse(new ASTVisitor() {
                @Override
                public boolean visit(MessageSend m, BlockScope scope) {
                    if (m.binding() instanceof ProblemMethodBinding)
                        throw exceptionVisitorInterrupted;
                    return true;
                }
                //TODO re-enable
//                @Override
//                public boolean visit(QualifiedTypeReference r, BlockScope scope) {
//                    if (r.resolvedType == null || r.resolvedType instanceof ProblemReferenceBinding)
//                        throw exceptionVisitorInterrupted;
//                    return true;
//                }
            }, scope);
        } catch(Exception e){
            if (e == exceptionVisitorInterrupted)
                return false;
        }
        return true;
    }

    static RuntimeException exceptionVisitorInterrupted = new RuntimeException();

    public static boolean codeContainsTestabilityFieldAccessExpression(CompilationUnitDeclaration unitDeclaration){

        ArrayList<Object> calls = new ArrayList<>();

        Consumer<Expression> checkIsFieldAccessExpression = (Expression ex) -> {

            if (calls.isEmpty() && Testability.isTestabilityFieldAccess(ex)) {
                calls.add(ex);
                throw exceptionVisitorInterrupted;
            }
        };

        try {
            unitDeclaration.traverse(new ASTVisitor() {

                @Override
                public boolean visit(MessageSend messageSend, BlockScope scope) {
                    checkIsFieldAccessExpression.accept(messageSend);
                    return super.visit(messageSend, scope);
                }
                @Override
                public boolean visit(QualifiedNameReference qnr, BlockScope scope) {
                    checkIsFieldAccessExpression.accept(qnr);
                    return super.visit(qnr, scope);
                }
                @Override
                public boolean visit(FieldReference fr, BlockScope scope) {
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
            CompilationResult compilationResult,
            ReferenceBinding typeBinding,
            SourceTypeBinding referenceBinding) {

        FieldDeclaration fieldDeclarationPreCreate = makeListenerFieldDeclaration(
                compilationResult,
                typeBinding,
                referenceBinding,
                makeListenerFieldName(typeBinding, "preCreate"));//"$$"+supertypeName+"preCreate");

        FieldDeclaration fieldDeclarationPostCreate = makeListenerFieldDeclaration(
                compilationResult,
                typeBinding,
                referenceBinding,
                makeListenerFieldName(typeBinding, "postCreate"));//"$$"+supertypeName+"postCreate");

        ArrayList<FieldDeclaration> ret = new ArrayList<>();

        ret.add(fieldDeclarationPreCreate);
        ret.add(fieldDeclarationPostCreate);

        return ret;
    }

    /**
     *
     * @param originalCallToField
     * @param typeDeclaration
     * @param referenceBinding
     * @param lookupEnvironment
     * @return unique field instances
     */
    public static List<FieldDeclaration> makeTestabilityRedirectorFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            LookupEnvironment lookupEnvironment, Consumer<Map<Expression, FieldDeclaration>> originalCallToFieldProducer) throws Exception {

        //eliminate duplicates, since multiple call of the same method possible
        Map<String, List<Map.Entry<Expression, TypeDeclaration>>> uniqueFieldToExpression = typeDeclaration.allCallsToRedirect.stream().
                collect(
                        Collectors.groupingBy(entry -> {
                            Expression expr = entry.getKey();
                            return testabilityFieldDescriptorUniqueInOverload(expr);
                        })
                );

        List<Map.Entry<Expression, TypeDeclaration>> distinctCalls = uniqueFieldToExpression.values().stream().
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

        List<FieldDeclaration> ret = //contains nulls
                IntStream.range(0, uniqueFieldNames.size()).
                mapToObj(pos -> {
                    Map.Entry<Expression, TypeDeclaration> entry = distinctCalls.get(pos);

                    Expression originalCall = entry.getKey();

                    TypeDeclaration typeDeclarationContainingCall = entry.getValue();

                    List<String> fieldNameParts = uniqueFieldNames.get(pos);

                    String fieldName = TESTABILITY_FIELD_NAME_PREFIX + fieldNameParts.stream().collect(joining(""));

                    FieldDeclaration fieldDeclaration = null;

                    try {
                        if (originalCall instanceof MessageSend) {
                            MessageSend originalMessageSend = (MessageSend) originalCall;

                            fieldDeclaration = makeRedirectorFieldDeclaration(
                                    originalMessageSend,
                                    typeDeclaration,
                                    typeDeclarationContainingCall,
                                    referenceBinding,
                                    fieldName
                            );
                        } else if (originalCall instanceof AllocationExpression) {
                            AllocationExpression originalAllocationExpression = (AllocationExpression) originalCall;

                            fieldDeclaration = makeRedirectorFieldDeclaration(
                                    originalAllocationExpression,
                                    typeDeclaration,
                                    typeDeclarationContainingCall,
                                    referenceBinding,
                                    fieldName
                            );
                        }
                    } catch(AbortType ex){
                        testabilityInstrumentationError(
                                typeDeclaration.scope,
                                "field " + fieldName + " cannot be created for expression " + originalCall +" due to prior errors");
                        return null;

                    } catch(Exception ex){
                        testabilityInstrumentationError(
                                typeDeclaration.scope,
                                "field " + fieldName + " cannot be created for expression " + originalCall,
                                ex);
                        return null;
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
                            if (field == null) {
                                return null;
                            }
                            return new AbstractMap.SimpleEntry<>(field, list);
                        }).
                        filter(Objects::nonNull).
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

//TODO reen or allways null?        lambdaExpression.setExpectedType(typeReferenceForFunction.resolvedType);

        return lambdaExpression;
    }

    static ReferenceBinding bindingForCallContextType(
            TypeBinding callingType,
            TypeBinding calledType,
            LookupEnvironment lookupEnvironment){

        char[][] path = {
                "testablejava".toCharArray(),
                "CallContext".toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", " + Arrays.stream(path).map(String::new).collect(joining(".")) + " not found");
        }

        ReferenceBinding enclosingType = null;

        return lookupEnvironment.createParameterizedType(
                genericType,
                new TypeBinding[]{callingType, calledType},
                enclosingType);

    }

    static FieldDeclaration makeRedirectorFieldDeclaration(
            MessageSend originalMessageSend,
            TypeDeclaration typeDeclaration,
            TypeDeclaration typeDeclarationContainingCall,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        TypeBinding fieldTypeBinding = originalMessageSend.binding.returnType;

        boolean returnsVoid = returnsVoid(originalMessageSend);

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        Expression[] originalArguments = originalMessageSend.arguments;
        if (originalArguments == null)
            originalArguments = new Expression[0];

        int typeArgsCount = 1 + originalArguments.length + (returnsVoid ? 0 : 1); //context, args (, return)

        TypeBinding[] typeArgumentsForFunction = new TypeBinding[typeArgsCount];

        int iArg = 0;

        TypeBinding receiverResolvedType = originalMessageSend.actualReceiverType;//originalMessageSend.receiver.resolvedType;

        TypeBinding callingType = convertIfAnonymous(typeDeclarationContainingCall.binding);

        //TODO apply elsewhere too, factor out a method:

        callingType = convertToLocalIfGeneric(callingType, lookupEnvironment);

        TypeBinding calledType = convertIfAnonymous(receiverResolvedType);

        calledType = convertToLocalIfGeneric(calledType, lookupEnvironment);

        typeArgumentsForFunction[iArg++] = bindingForCallContextType(
                callingType,
                calledType, //this should be apparent compile type called
                lookupEnvironment);

        TypeBinding[] originalBindingParameters = originalMessageSend.binding.parameters;

        boolean varargs = false;

        for (Expression arg : originalArguments) {
            TypeBinding argType = arg.resolvedType;

            int iArgOriginal = iArg - 1;

            TypeBinding argTypeOriginal = iArgOriginal < originalBindingParameters.length?
                    originalBindingParameters[iArgOriginal] : null;

            //special-case NullTypeBinding, get resolved version
            if (argType instanceof NullTypeBinding)
                argType = argTypeOriginal;

            //special-case vararg case, need an array type even though compiler type can be single
            if (argTypeOriginal instanceof ArrayBinding &&
                    argType != null &&
                    !(argType instanceof ArrayBinding)) {
                argType = argTypeOriginal;
                varargs = true;
                //last arg is array, skip the rest
                typeArgumentsForFunction[iArg++] = boxIfApplicable(argType, lookupEnvironment);

                break;
            }
            typeArgumentsForFunction[iArg++] = boxIfApplicable(argType, lookupEnvironment);
        }

        if (!returnsVoid)
            typeArgumentsForFunction[iArg++] = boxIfApplicable(fieldTypeBinding, lookupEnvironment);

        //truncate the rest
        typeArgumentsForFunction = Arrays.copyOf(typeArgumentsForFunction, iArg);

        //replace references to type arguments with Object type
        ReferenceBinding objectTypeBinding = lookupEnvironment.askForType(new char[][]{"java".toCharArray(), "lang".toCharArray(), "Object".toCharArray()});
        for (int iType = 0; iType <typeArgumentsForFunction.length;iType++){
            if (typeArgumentsForFunction[iType] instanceof TypeVariableBinding){
                typeArgumentsForFunction[iType] = objectTypeBinding;
            }
        }

        //rewire anonymous inner classes to their parents
        for (int iTypeArg=0; iTypeArg<typeArgumentsForFunction.length; iTypeArg++){
            TypeBinding typeBinding = typeArgumentsForFunction[iTypeArg];
            if (typeBinding.isAnonymousType())
                typeArgumentsForFunction[iTypeArg] = typeArgumentsForFunction[iTypeArg].superclass();
        }
        //TODO test!
        for (int iTypeArg=0; iTypeArg<typeArgumentsForFunction.length; iTypeArg++){
            TypeBinding typeBinding = typeArgumentsForFunction[iTypeArg];

            typeArgumentsForFunction[iTypeArg] = convertCaptureBinding(typeBinding);

        }

//        //TODO//see if any type argument is CaptureBinding, and replace it with its sourceType
//        if (receiverResolvedType instanceof ParameterizedTypeBinding){
//            ParameterizedTypeBinding receiverResolvedTypeParameterized = (ParameterizedTypeBinding) receiverResolvedType;
//            TypeBinding[] originalArgs = receiverResolvedTypeParameterized.arguments;
//            TypeBinding [] newArgs = Arrays.stream(originalArgs).
//                    map(arg -> (arg instanceof CaptureBinding)?((CaptureBinding) arg).sourceType: arg).
//                    collect(toList()).
//                    toArray(new TypeBinding[originalArgs.length]);
//            receiverResolvedTypeParameterized.arguments = newArgs;
//
//        }

        int functionArgCount = typeArgumentsForFunction.length - (returnsVoid ? 0 : 1);

        int additionalTypeVarCountForMethod = originalMessageSend.arguments==null?
                0 :
                (int) Arrays.stream(originalMessageSend.binding.parameters).
                        filter(TypeVariableBinding.class::isInstance).
                        count();

        char[][] path = {
                "helpers".toCharArray(),
                functionNameForArgs(
                        returnsVoid,
                        functionArgCount,
                        additionalTypeVarCountForMethod).toCharArray()

        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            Testability.testabilityInstrumentationError(
                    typeDeclaration.scope,
                    "missing helper type: " +
                            Arrays.stream(path).
                                    map(String::new).
                                    collect(joining(".")));
        }

        ParameterizedTypeBinding typeBindingForFunction =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArgumentsForFunction,
                        referenceBinding);

        if (varargs) {
            char[] selector = (returnsVoid? TARGET_REDIRECTED_METHOD_NAME_FOR_CONSUMER : TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION).toCharArray();
            MethodBinding[] methods = typeBindingForFunction.actualType().getMethods(selector);
            if (methods.length == 0)
                throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", no methods on functional interface on type " + typeBindingForFunction.actualType());
            methods[0].modifiers |= ClassFileConstants.AccVarargs;
        }

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

        fieldDeclaration.type = parameterizedQualifiedTypeReferenceForFunction;

        fieldDeclaration.modifiers = ClassFileConstants.AccPublic | ClassFileConstants.AccStatic;

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBindingForFunction,
                fieldDeclaration.modifiers,
                typeDeclaration.binding.outermostEnclosingType());

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed?


        LambdaExpression lambdaExpression = makeLambdaExpression(
                originalMessageSend,
                typeDeclarationContainingCall,
                lookupEnvironment,
                typeArgumentsForFunction,
                typeBindingForFunction);

        MessageSend messageSendInLambdaBody = new MessageSend();
        messageSendInLambdaBody.selector = originalMessageSend.selector;

        //first argument is always context:  (arg0, arg1, .. argN) -> arg0.calledClassInstance.apply(arg1, .. argN)

        {
            char[][] receiverPathDynamicCall = { " arg0".toCharArray(), "calledClassInstance".toCharArray()};

            Expression newReceiverDynamicCall =
                    new QualifiedNameReference(receiverPathDynamicCall, new long[receiverPathDynamicCall.length], 0, 0);

            messageSendInLambdaBody.receiver = originalMessageSend.binding.isStatic()?
                    originalMessageSend.receiver :
                    newReceiverDynamicCall;
        }

//        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = null;//this is to resolve expression without apparent receiver

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        int methodSendInLambdaBodyArgCount = typeArgumentsForFunction.length - 1 - (returnsVoid ? 0 : 1);

        Expression[] argv = new Expression[methodSendInLambdaBodyArgCount];
        int iArgCastTypeVar = 0;
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + 1)).toCharArray();

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);

            if (originalMessageSend.arguments[i].resolvedType instanceof TypeVariableBinding) {
                char[] sourceName = ("E" + (iArgCastTypeVar++ + 1)).toCharArray();//((TypeVariableBinding) originalMessageSend.arguments[i].resolvedType).sourceName;
                TypeReference typeReference = new SingleTypeReference(
                        sourceName, 0);

                CastExpression castExpression = new CastExpression(singleNameReference, typeReference);
                argv[i] = castExpression;
            }
            else {
                argv[i] = singleNameReference;
            }
        }

        if (argv.length != 0) {
            messageSendInLambdaBody.arguments = argv; //otherwise stays 0, resolution logic depends on it
//            //make type variables available on field class binding
//            //TODO exper
//            List<Expression> typeVars = Arrays.stream(originalMessageSend.arguments).
//                    map(ex -> ex.resolvedType).
//                    filter(TypeVariableBinding.class::isInstance).
//                    map(type -> type.sourceName()).
//                    map(sourceName -> new SingleTypeReference(sourceName, 0)).
//                    collect(toList());
//            if (!typeVars.isEmpty())
//                messageSendInLambdaBody.typeArguments = typeVars.toArray(new TypeReference[0]);
        }


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
        if (!(originalMessageSend.binding instanceof ParameterizedGenericMethodBinding)) {
            fieldDeclaration.initialization = lambdaExpression;
        } else {

            TypeDeclaration anonymousType = new TypeDeclaration(typeDeclaration.compilationResult);

            if (anonymousType.methods == null)
                anonymousType.methods = new MethodDeclaration[]{};
            anonymousType.methods = Arrays.copyOf(anonymousType.methods, anonymousType.methods.length + 1);
            MethodDeclaration methodDeclaration = new MethodDeclaration(typeDeclaration.compilationResult);

            anonymousType.methods[anonymousType.methods.length - 1] = methodDeclaration;

            methodDeclaration.binding = typeBindingForFunction.getSingleAbstractMethod(typeDeclaration.scope, true);
            methodDeclaration.binding.modifiers ^= ClassFileConstants.AccAbstract;
            methodDeclaration.binding.modifiers |= ClassFileConstants.AccPublic;
            methodDeclaration.selector = methodDeclaration.binding.selector;
            methodDeclaration.modifiers = methodDeclaration.binding.modifiers;

            methodDeclaration.arguments = lambdaExpression.arguments;
            methodDeclaration.returnType = typeReferenceFromTypeBinding(methodDeclaration.binding.returnType);
            methodDeclaration.statements = block.statements;

            //each type parameter on method used to cast an argument of original call, and they are named E1...N where N is number of original call arguments
            if (additionalTypeVarCountForMethod > 0) {
                methodDeclaration.typeParameters = IntStream.range(0, additionalTypeVarCountForMethod).
                        mapToObj(iTypeParameter -> {
                            TypeParameter ret = new TypeParameter();
                            ret.name = ("E" + (iTypeParameter + 1)).toCharArray();
                            return ret;
                        }).
                        collect(toList()).
                        toArray(new TypeParameter[0]);
            }

            anonymousType.name = CharOperation.NO_CHAR;
            anonymousType.bits |= (ASTNode.IsAnonymousType|ASTNode.IsLocalType);
            QualifiedAllocationExpression alloc = new QualifiedAllocationExpression(anonymousType);

            alloc.type = typeReferenceFromTypeBinding(typeBindingForFunction);

            fieldDeclaration.initialization = alloc;
        }
        return fieldDeclaration;
    }

    private static TypeBinding convertToLocalIfGeneric(TypeBinding callingType, LookupEnvironment lookupEnvironment) {
        if (callingType.isGenericType())
            callingType = lookupEnvironment.convertToRawType(callingType, false);
        return callingType;
    }

//    /**
//     * make any parameterized type variables wildcards
//     * @param typeBinding
//     * @param lookupEnvironment
//     * @return
//     */
//    static TypeBinding removeAnyTypeVariables(TypeBinding typeBinding, LookupEnvironment lookupEnvironment) {
//
//        if (typeBinding instanceof ParameterizedTypeBinding) {
//            return lookupEnvironment.createRawType(((ParameterizedTypeBinding) typeBinding).genericType(), typeBinding.enclosingType());
//        }
//
////        TypeBinding[] types = lookupEnvironment.getDerivedTypes(typeBinding);
////        TypeBinding retTypeBinding = typeBinding.clone(typeBinding.enclosingType());
////
////        if (retTypeBinding instanceof SourceTypeBinding) {
////            TypeVariableBinding[] newTypeVariables = ((SourceTypeBinding) retTypeBinding).typeVariables;
////
////            IntStream.range(0, newTypeVariables.length).
////                    forEach(i-> newTypeVariables[i] = new WildcardBinding(Wildcard.UNBOUND));
////
////            ((SourceTypeBinding) retTypeBinding).typeVariables = newTypeVariables;
////
////        } else if (retTypeBinding instanceof ParameterizedTypeBinding) {
////            ((ParameterizedTypeBinding) retTypeBinding).arguments = null;
////        }
////
////        return retTypeBinding;
//
//    }

    static TypeBinding convertIfAnonymous(TypeBinding binding) {
        if (!binding.isAnonymousType())
            return binding;
        return convertIfLocal(binding);
    }

    /**
     * given local binding, return a 'parent' binding, which could be actual parent class or interface
     * @param binding
     * @return
     */
    static TypeBinding convertIfLocal(TypeBinding binding) {
        if (!(binding instanceof LocalTypeBinding))
          return binding;
        LocalTypeBinding localTypeBinding = (LocalTypeBinding) binding;
        if (localTypeBinding.superclass != null && localTypeBinding.superclass.superclass()!=null)
            return localTypeBinding.superclass;
        if (localTypeBinding.superInterfaces().length != 0){
            return localTypeBinding.superInterfaces()[0]; //cannot derive from more than one interface to make a local class
        }
        return (localTypeBinding.superclass != null)? localTypeBinding.superclass : binding;
    }

    static TypeBinding convertCaptureBinding(TypeBinding typeBinding) {
        if (typeBinding instanceof CaptureBinding) {
            return ((CaptureBinding) typeBinding).sourceType;
//            return typeBinding.clone(typeBinding.enclosingType());
        }
        if (typeBinding instanceof ParameterizedTypeBinding && !typeBinding.isRawType()) {
            ParameterizedTypeBinding typeBindingParameterized =
                    (ParameterizedTypeBinding) typeBinding;
            TypeBinding[] originalArgs = typeBindingParameterized.arguments;
            TypeBinding[] newArgs = Arrays.stream(originalArgs).
                    map(arg -> convertCaptureBinding(arg)).
                    collect(toList()).
                    toArray(new TypeBinding[originalArgs.length]); //TODO this is partially in-place conversion, partially new return

            typeBindingParameterized.arguments = newArgs;

            return typeBindingParameterized;
        }
        return typeBinding;
    }

    static FieldDeclaration makeRedirectorFieldDeclaration(
            AllocationExpression originalMessageSend,
            TypeDeclaration typeDeclaration,
            TypeDeclaration typeDeclarationContainingCall,
            SourceTypeBinding referenceBinding,
            String fieldName) {

        TypeBinding fieldTypeBinding =
                originalMessageSend.binding.declaringClass;

        if (new String(fieldTypeBinding.shortReadableName()).equals("void")) {
            TypeBinding tvoid = new SingleTypeReference("Void".toCharArray(), -1).resolveType(referenceBinding.scope);
            fieldTypeBinding = tvoid; //TODO is this called?
        }

        FieldDeclaration fieldDeclaration = new FieldDeclaration(fieldName.toCharArray(), 0, 0);

        LookupEnvironment lookupEnvironment = referenceBinding.scope.environment();

        Expression[] originalArguments = originalMessageSend.arguments;
        if (originalArguments == null)
            originalArguments = new Expression[0];

        int typeArgsCount = originalArguments.length + 2; // context + args + return types

        TypeBinding[] typeArguments = new TypeBinding[typeArgsCount];

        TypeBinding callingType = convertIfLocal(typeDeclarationContainingCall.binding);
        callingType = convertToLocalIfGeneric(callingType, lookupEnvironment);

        TypeBinding calledType = convertIfLocal(originalMessageSend.resolvedType);
        calledType = convertToLocalIfGeneric(calledType, lookupEnvironment);

        typeArguments[0] = bindingForCallContextType(
                callingType,
                calledType,
                lookupEnvironment);

        int iArg = 1;
        TypeBinding[] originalBingingParameters = originalMessageSend.binding.parameters;

        boolean varargs = false;

        for (Expression arg : originalArguments) {
            TypeBinding argType = arg.resolvedType;

            int iArgOriginal = iArg - 1;

            TypeBinding argTypeOriginal = iArgOriginal < originalBingingParameters.length?
                    originalBingingParameters[iArgOriginal] : null;

            //special-case NullTypeBinding, get resolved version
            if (argType instanceof NullTypeBinding)
                argType = argTypeOriginal;

            //special-case vararg case, need an array type even though compiler type can be single
            if (argTypeOriginal instanceof ArrayBinding &&
                argType != null &&
                !(argType instanceof ArrayBinding)) {
                argType = argTypeOriginal;
                varargs = true;
                //last arg is array, skip the rest
                typeArguments[iArg++] = boxIfApplicable(argType, lookupEnvironment);

                break;
            }
            typeArguments[iArg++] = boxIfApplicable(argType, lookupEnvironment);
        }
        typeArguments[iArg++] = fieldTypeBinding;

        //truncate the rest
        typeArguments = Arrays.copyOf(typeArguments, iArg);

        //replace references to type arguments with Object type
        ReferenceBinding objectTypeBinding = lookupEnvironment.askForType(new char[][]{"java".toCharArray(), "lang".toCharArray(), "Object".toCharArray()});
        for (int iType = 0; iType <typeArguments.length;iType++){
            if (typeArguments[iType] instanceof TypeVariableBinding){
                typeArguments[iType] = objectTypeBinding;
            }
        }

        //rewire anonymous inner classes to their parents
        for (int iTypeArg=0; iTypeArg<typeArguments.length; iTypeArg++){
            TypeBinding typeBinding = typeArguments[iTypeArg];
            if (typeBinding.isAnonymousType())
                typeArguments[iTypeArg] = typeArguments[iTypeArg].superclass();
        }

        int functionArgCount = typeArguments.length - 1;

        int additionalTypeVarCountForMethod = (int) Arrays.stream(originalMessageSend.binding.parameters).filter(TypeVariableBinding.class::isInstance).count();

        char[][] path = {
                "helpers".toCharArray(),
                functionNameForArgs(
                        false,
                        functionArgCount,
                        additionalTypeVarCountForMethod).toCharArray()
        };

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        if (genericType == null) {
            if (genericType == null) {
                Testability.testabilityInstrumentationError(
                        typeDeclaration.scope,
                        "missing helper type: " +
                                Arrays.stream(path).
                                        map(String::new).
                                        collect(joining(".")));
            }
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

        if (varargs) {
            char[] selector = TARGET_REDIRECTED_METHOD_NAME_FOR_FUNCTION.toCharArray();
            MethodBinding[] methods = typeBinding.actualType().getMethods(selector);
            if (methods.length == 0)
                throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", no methods on functional interface on type " + typeBinding.actualType());
            methods[0].modifiers |= ClassFileConstants.AccVarargs;
        }

        fieldDeclaration.type = parameterizedQualifiedTypeReference;

        fieldDeclaration.modifiers = ClassFileConstants.AccPublic | ClassFileConstants.AccStatic;

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                typeBinding,
                fieldDeclaration.modifiers,
                typeDeclaration.binding.outermostEnclosingType());

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

        LambdaExpression lambdaExpression = new LambdaExpression(
                typeDeclaration.compilationResult,
                false);
        //see ReferenceExpression::generateImplicitLambda

        int argc = typeArguments.length - 1; //type args has return at the end, method args do not
        Argument[] arguments = new Argument[argc];
        for (int i = 0; i < argc; i++) {
            TypeBinding typeBindingForArg = boxIfApplicable(typeBinding.arguments[i], lookupEnvironment);
            TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
            arguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
        }

        lambdaExpression.setArguments(arguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(fieldDeclaration.type.resolvedType);

        AllocationExpression messageSendInLambdaBody;

        if (originalMessageSend.resolvedType instanceof MemberTypeBinding){ //enclosing class instance not available, explicit call via arg0.callingClassInstance
            QualifiedAllocationExpression inLambdaBodyQualifiedAllocationExpression =
                    new QualifiedAllocationExpression();

            char[][] receiverPathDynamicCall = { " arg0".toCharArray(), "callingClassInstance".toCharArray()};

            Expression enclosingInstanceCall =
                    new QualifiedNameReference(receiverPathDynamicCall, new long[receiverPathDynamicCall.length], 0, 0);

            inLambdaBodyQualifiedAllocationExpression.enclosingInstance = enclosingInstanceCall;
            messageSendInLambdaBody = inLambdaBodyQualifiedAllocationExpression;
        } else {

            if (originalMessageSend instanceof QualifiedAllocationExpression) {
                Testability.testabilityInstrumentationWarning(referenceBinding.scope, "cannot redirect anonymous class allocation: " + originalMessageSend);
                return null;
            } else {
                messageSendInLambdaBody = new AllocationExpression();
            }
        }
        messageSendInLambdaBody.type = originalMessageSend.type;
        messageSendInLambdaBody.setExpectedType(originalMessageSend.invocationTargetType());
//        messageSendInLambdaBody.typeArguments = originalMessageSend.typeArguments;
        messageSendInLambdaBody.binding = originalMessageSend.binding;//TODO null to match MessageSend version? see "fixed static import case' commit

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        int parameterShift = 1; //arg 0 sent in is context, skip

        Expression[] argv = new Expression[argc - parameterShift];
        int iArgCastTypeVar = 0;
        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + parameterShift)).toCharArray();

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);

            if (originalMessageSend.arguments[i].resolvedType instanceof TypeVariableBinding) {
                char[] sourceName = ("E" + (iArgCastTypeVar++ + 1)).toCharArray();//((TypeVariableBinding) originalMessageSend.arguments[i].resolvedType).sourceName;
                TypeReference typeReference = new SingleTypeReference(
                        sourceName, 0);

                CastExpression castExpression = new CastExpression(singleNameReference, typeReference);
                argv[i] = castExpression;
            }
            else {
                argv[i] = singleNameReference;
            }
        }
        if (argv.length != 0) {
            messageSendInLambdaBody.arguments = argv;
        }

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

        if (!(originalMessageSend.binding instanceof ParameterizedGenericMethodBinding)) {
            fieldDeclaration.initialization = lambdaExpression;
        } else {
            TypeDeclaration anonymousType = new TypeDeclaration(typeDeclaration.compilationResult);

            if (anonymousType.methods == null)
                anonymousType.methods = new MethodDeclaration[]{};
            anonymousType.methods = Arrays.copyOf(anonymousType.methods, anonymousType.methods.length + 1);
            MethodDeclaration methodDeclaration = new MethodDeclaration(typeDeclaration.compilationResult);

            anonymousType.methods[anonymousType.methods.length - 1] = methodDeclaration;

            methodDeclaration.binding = typeBinding.getSingleAbstractMethod(typeDeclaration.scope, true);
            methodDeclaration.binding.modifiers ^= ClassFileConstants.AccAbstract;
            methodDeclaration.binding.modifiers |= ClassFileConstants.AccPublic;
            methodDeclaration.selector = methodDeclaration.binding.selector;
            methodDeclaration.modifiers = methodDeclaration.binding.modifiers;

            methodDeclaration.arguments = lambdaExpression.arguments;
            methodDeclaration.returnType = typeReferenceFromTypeBinding(methodDeclaration.binding.returnType);
            methodDeclaration.statements = block.statements;

            //each type parameter on method used to cast an argument of original call, and they are named E1...N where N is number of original call arguments
            if (additionalTypeVarCountForMethod > 0) {
                methodDeclaration.typeParameters = IntStream.range(0, additionalTypeVarCountForMethod).
                        mapToObj(iTypeParameter -> {
                            TypeParameter ret = new TypeParameter();
                            ret.name = ("E" + (iTypeParameter + 1)).toCharArray();
                            return ret;
                        }).
                        collect(toList()).
                        toArray(new TypeParameter[0]);
            }

            anonymousType.name = CharOperation.NO_CHAR;
            anonymousType.bits |= (ASTNode.IsAnonymousType|ASTNode.IsLocalType);

            QualifiedAllocationExpression alloc = new QualifiedAllocationExpression(anonymousType);

            alloc.type = typeReferenceFromTypeBinding(typeBinding);

            fieldDeclaration.initialization = alloc;
        }
        return fieldDeclaration;
    }

    static boolean returnsVoid(MessageSend messageSend) {
        return messageSend.binding.returnType instanceof VoidTypeBinding &&
                !messageSend.binding.isConstructor(); //somehow constructors have void return type
    }

    static FieldDeclaration makeListenerFieldDeclaration(
            CompilationResult compilationResult,
            ReferenceBinding typeDeclarationBinding,
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
            throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", " + new String(path[0]) + " not found");
        }

        TypeBinding resultingTypeBinding = Testability.boxIfApplicable(convertIfAnonymous(typeDeclarationBinding), lookupEnvironment);

        TypeBinding[] typeArguments //class
                = {resultingTypeBinding};

        ParameterizedTypeBinding typeBinding =
                lookupEnvironment.createParameterizedType(
                        genericType,
                        typeArguments,
                        referenceBinding);

        TypeReference[][] typeReferences = new TypeReference[path.length][];

        typeReferences[path.length - 1] = new TypeReference[]{Testability.typeReferenceFromTypeBinding(resultingTypeBinding)};

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
                typeDeclarationBinding.outermostEnclosingType());

        fieldDeclaration.binding = fieldBinding;
        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

        LambdaExpression lambdaExpression = new LambdaExpression(compilationResult, false);

        Argument[] arguments = new Argument[1]; //TODO use this method in other make..FieldDeclaration
        TypeReference typeReference =
                Testability.typeReferenceFromTypeBinding(
                        resultingTypeBinding);

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
            throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", " + new String(path[0]) + " not found");
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
                typeDeclaration.binding.outermostEnclosingType());

        fieldDeclaration.binding = fieldBinding;
//        fieldDeclaration.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature; //TODO needed? see  makeRedirectorFieldDeclaration for message

        fieldDeclaration.initialization = new NullLiteral(0,0);
        return fieldDeclaration;
    }

    /**
     *
     * @param returnsVoid true for Consumer and false for Function
     * @param functionArgCount actual number of arguments passed to the function,
     *                         e.g. one less than type arguments for Function
     *                         and exactly the count of type args for Consumer
     * @param additionalTypeVarCountForMethod
     * @return
     */
    static String functionNameForArgs(boolean returnsVoid, int functionArgCount, int additionalTypeVarCountForMethod) {
        String name = (
                returnsVoid ?
                        "Consumer" :
                        "Function"
                ) +
                functionArgCount +
                (additionalTypeVarCountForMethod>0 ?
                        "_"+additionalTypeVarCountForMethod :
                        "");

        return name;
    }

    static public TypeReference typeReferenceFromTypeBinding(TypeBinding typeBinding) {
        int dim = typeBinding.dimensions();
        if (dim == 0){
//            if (typeBinding instanceof TypeVariableBinding) { //TODO experiment
//                //replace with ?
//                Wildcard wildcard = new Wildcard(Wildcard.UNBOUND);
//                wildcard.bound = null;
//                return wildcard;
//            }
            if (typeBinding instanceof TypeVariableBinding) { //TODO experiment
                //replace with Object, if replacing with ?, will get declarations like '? arg'
                SingleTypeReference objectTypeReference = new SingleTypeReference("Object".toCharArray(), 0);

                return objectTypeReference;
            }
            if (typeBinding instanceof ReferenceBinding) {
                ReferenceBinding binaryTypeBinding = (ReferenceBinding) typeBinding;
                if (typeBinding instanceof WildcardBinding) {
                    Wildcard wildcard = new Wildcard(((WildcardBinding) typeBinding).boundKind);
                    wildcard.bound = typeReferenceFromTypeBinding(((WildcardBinding) typeBinding).bound);
                    return wildcard;
                }

//                if (binaryTypeBinding instanceof CaptureBinding){ //TODO causing type mismatch
//                    return typeReferenceFromTypeBinding(((CaptureBinding) binaryTypeBinding).sourceType);
//                }

                char[][] compoundName = binaryTypeBinding.compoundName!=null?
                        expandInternalName(removeLocalPrefix(binaryTypeBinding.compoundName)) :
                        new char[][]{binaryTypeBinding.sourceName}
                        ;
                if (binaryTypeBinding.compoundName == null && !(binaryTypeBinding instanceof TypeVariableBinding))
                    Testability.testabilityInstrumentationWarning(null,"binding has null compound name: " + binaryTypeBinding.getClass().getName());

                if (!typeBinding.isParameterizedType()) {
                    return new QualifiedTypeReference(compoundName, new long[compoundName.length]);
                } else {
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;
                    TypeReference[] typeArguments = new TypeReference[parameterizedTypeBinding.arguments.length];

                    Arrays.stream(parameterizedTypeBinding.arguments).
                            map(Testability::typeReferenceFromTypeBinding).
                            collect(Collectors.toList()).toArray(typeArguments);

                    TypeReference[][] typeArgumentsCompound = new TypeReference[compoundName.length][];//{typeArguments};
                    typeArgumentsCompound[typeArgumentsCompound.length - 1] = typeArguments;

                    return new ParameterizedQualifiedTypeReference(
                            compoundName,
                            typeArgumentsCompound,
                            dim,
                            new long[compoundName.length]
                    );
                }
            } else if (typeBinding instanceof NullTypeBinding){
                throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ", NullTypeBinding passed in");
            } else if (typeBinding instanceof BaseTypeBinding){
                return TypeReference.baseTypeReference(typeBinding.id, 0);
            } else { //TODO will this ever happen?
                char[][] sourceName = expandInternalName(typeBinding.sourceName());
                if (!typeBinding.isParameterizedType()) {
                    return new QualifiedTypeReference(
                            sourceName, new long[sourceName.length]
                    );
                } else {
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;

                    TypeReference[] typeArguments = new TypeReference[parameterizedTypeBinding.arguments.length];

                    Arrays.stream(parameterizedTypeBinding.arguments).
                            map(Testability::typeReferenceFromTypeBinding).
                            collect(Collectors.toList()).toArray(typeArguments);

                    TypeReference[][] typeArgumentsCompound = new TypeReference[sourceName.length][];//{typeArguments};
                    typeArgumentsCompound[typeArgumentsCompound.length - 1] = typeArguments;

                    return new ParameterizedQualifiedTypeReference(
                            sourceName,
                            typeArgumentsCompound,
                            dim,
                            new long[sourceName.length]
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
                    typeName,
                    dim,
                    poss
            );
        }
    }

    static char[][] removeLocalPrefix(char[][] compoundName) {
        return Arrays.stream(compoundName).
                map(chunk -> removeLocalPrefix(chunk)).
                collect(toList()).
                toArray(new char[][]{});
    }

    /**
     *
     * @param name
     * @return name without the $Local$ prefix. The prefix appears for local classes
     */
    static char[] removeLocalPrefix(char[] name) {
        String internalNameStr = new String(name);
        String localPrefix = "$Local$";
        if (internalNameStr.startsWith(localPrefix)) {
            return internalNameStr.substring(localPrefix.length()).toCharArray();
        }

        return name;
    }

    /**
     *
     * @param compoundName
     * @return deep copy of arg where in each element with '$' replaced with '.'
     */
    static char[][] expandInternalName(char[][] compoundName) {
        return Arrays.stream(compoundName).
                flatMap(chunk -> Arrays.stream(expandInternalName(chunk))).
                collect(toList()).
                toArray(new char[][]{});
    }

    /**
     *
     * @param internalName
     * @return copy of arg split by '$'
     */
    static char[][] expandInternalName(char[] internalName) {
        String internalNameStr = new String(internalName);

        String[] chunks = internalNameStr.split("\\$");

        char [][] ret = new char[chunks.length][];

        for (int i = 0; i < chunks.length; i++) {
            ret[i] = chunks[i].toCharArray();
        }
        return ret;
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

            //note: ThisReference in case of statically imported class (and static call) is a confusing case,
            // for which we will use declaringClass
            ReferenceBinding receiverReferenceBinding;
            if (receiverBinding instanceof ReferenceBinding &&
                    !(originalMessageSend.receiver instanceof ThisReference)) {
                receiverReferenceBinding = (ReferenceBinding) receiverBinding;
            } else {
                receiverReferenceBinding = binding.declaringClass;
            }

            if (receiverReferenceBinding.isAnonymousType()){
                receiverReferenceBinding = ((LocalTypeBinding) receiverReferenceBinding).superclass;
            }

            invokedClassName = new String(readableName(receiverReferenceBinding, shortClassName));

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
            ReferenceBinding receiverReferenceBinding = binding.declaringClass;

            if (receiverReferenceBinding.isAnonymousType()){
                receiverReferenceBinding = ((LocalTypeBinding) receiverReferenceBinding).superclass;
            }

            String invokedClassName = new String(readableName(receiverReferenceBinding, shortClassName));

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
     * @param typeBinding
     */
    public static void addListenerCallsToConstructor(
            ConstructorDeclaration constructorDeclaration,
            ReferenceBinding typeBinding) {

        Set<InstrumentationOptions> instrumentationOptions = getInstrumentationOptions(constructorDeclaration.scope.classScope());

        if (!instrumentationOptions.contains(InstrumentationOptions.INSERT_LISTENERS))
            return;

        if (constructorDeclaration.statements == null)
            constructorDeclaration.statements = new Statement[]{};

        List<ParameterizedTypeBinding> typeInstances = getInstantiatedTypeBindings(
                Arrays.asList(typeBinding),
                constructorDeclaration.scope.environment());

        if (!typeInstances.isEmpty()){ //TODO form pre-post lists and update type once
            typeInstances.forEach(typeInstance -> addListenerCallsToConstructor(constructorDeclaration, typeInstance));
            return;
        }

        int originalStatementsCount = constructorDeclaration.statements.length;

        Statement [] newStatements = Arrays.copyOf(constructorDeclaration.statements, originalStatementsCount +2);
        System.arraycopy(constructorDeclaration.statements, 0, newStatements, 1, originalStatementsCount);

        newStatements[0] = statementForListenerCall(
                constructorDeclaration,
                typeBinding,
                "accept",
                "preCreate");

        newStatements[newStatements.length - 1] = statementForListenerCall(
                constructorDeclaration,
                typeBinding,
                "accept",
                "postCreate");

        constructorDeclaration.statements = newStatements;

    }

    static Statement statementForListenerCall(
            ConstructorDeclaration constructorDeclaration,
            ReferenceBinding typeBinding,
            String methodNameInListenerField,
            String targetFieldNameSuffix) {

        LookupEnvironment lookupEnvironment = constructorDeclaration.scope.environment();//typeDeclaration.scope.environment();

        TypeBinding resultingTypeBinding = Testability.boxIfApplicable(convertIfLocal(typeBinding), lookupEnvironment);

        MessageSend messageToFieldApply = new MessageSend();

        messageToFieldApply.selector = methodNameInListenerField.toCharArray();

        ReferenceBinding outerTypeBinding = typeBinding.outermostEnclosingType();

        String targetFieldName = makeListenerFieldName(typeBinding, targetFieldNameSuffix);

        NameReference fieldNameReference = makeQualifiedNameReference(
                new String(outerTypeBinding.sourceName()),
                targetFieldName);

        messageToFieldApply.receiver = fieldNameReference;

        messageToFieldApply.actualReceiverType = resultingTypeBinding;//messageToFieldApply.receiver.resolvedType;

        messageToFieldApply.arguments = new Expression[]{
                new CastExpression(
                        new ThisReference(0,0),
                        typeReferenceFromTypeBinding(resultingTypeBinding)
                )
        };

        LabeledStatement labeledStatement = new LabeledStatement(
                (DONTREDIRECT + "top" + System.nanoTime()).toCharArray(),
                messageToFieldApply, 0, 0);

        labeledStatement.targetLabel = new BranchLabel(); //normally done in analyseCode

        { //for some reason lambda constructor scope is static, but we need to use this to register instance with pre/post listeners
            boolean originalIsStatic = constructorDeclaration.scope.isStatic;

            constructorDeclaration.scope.isStatic = false;

            labeledStatement.resolve(constructorDeclaration.scope);
            constructorDeclaration.scope.isStatic = originalIsStatic;
        }

        if (null == messageToFieldApply.receiver.resolvedType)
            throw new RuntimeException(TESTABLEJAVA_INTERNAL_ERROR + ": unresolved field " + fieldNameReference);

        return labeledStatement;
    }

    static String makeListenerFieldName(TypeBinding typeBinding, String targetFieldNameSuffix) {
        String typeNamePrefix = "";
        if (typeBinding.isAnonymousType()) {
            LocalTypeBinding localTypeBinding = (LocalTypeBinding) typeBinding;
            QualifiedAllocationExpression allocation = localTypeBinding.scope.referenceContext.allocation.anonymousType.allocation;
            typeNamePrefix = escapeTypeArgsInTypeName(allocation.type.toString());//typeDeclaration.allocation.anonymousType.allocation.type.toString()) + "$";
        } else if (typeBinding.isLocalType()) {
            typeNamePrefix = escapeTypeArgsInTypeName(
                    new String(
                            removeLocalPrefix(typeBinding.readableName()))); //TODO type variables //TODO correct name
        }

        return "$$" +
                (typeNamePrefix.isEmpty() ? "" : typeNamePrefix + "$") +
                targetFieldNameSuffix;

    }

    public static boolean codeContainsSyntaxErrors(CompilationResult fullParseUnitResult) {
        return fullParseUnitResult.hasSyntaxError;
    }
}
