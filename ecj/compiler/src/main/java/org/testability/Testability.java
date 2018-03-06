package org.testability;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.InstrumentationOptions;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.BranchLabel;
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
import static org.testability.Testability.makeSingleNameReference;
import static org.testability.Testability.typeReferenceFromTypeBinding;

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

        if (!classDeclaration.compilationResult.instrumentForTestability)
            return false;

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
            TypeBinding calledTypeBinding = ((ParameterizedTypeBinding) ((ParameterizedTypeBinding) messageToFieldApply.actualReceiverType).arguments[0]).arguments[0];

             //(use actual called type binind used in field - to avoid re-computation)
            callSiteExpression = makeCallSiteExpression(messageSend, currentScope, calledTypeBinding);
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

    static AllocationExpression makeCallSiteExpression(MessageSend messageSend, BlockScope currentScope, TypeBinding calledTypeBinding) {

        LookupEnvironment lookupEnvironment = currentScope.environment();

        AllocationExpression allocationExpression = new AllocationExpression();

        TypeBinding callingTypeBindingForDescription = convertIfAnonymous(currentScope.classScope().referenceContext.binding);

        TypeBinding calledTypeBindingForDescription = messageSend.actualReceiverType;

        //field may contain specialization of generic type, while resolved apply method arguments can be generic, resulting in mismatch,
        //so convert everything to raw types which is how things are in bytecode anyway

        TypeBinding calledTypeBindingRaw = lookupEnvironment.convertToRawType(calledTypeBinding, false);

        ReferenceBinding callSiteTypeBinging = bindingForCallContextType(
                calledTypeBindingRaw,
                currentScope.environment()
        );

        TypeReference callSiteType = typeReferenceFromTypeBinding(callSiteTypeBinging);

        allocationExpression.type = callSiteType;

        Expression exprGetCallingClass = new StringLiteral(removeLocalPrefix(callingTypeBindingForDescription.readableName()), 0,0,0);

        Expression exprGetCalledClass = new StringLiteral(removeLocalPrefix(calledTypeBindingForDescription.readableName()), 0,0,0);

        TypeReference calledTypeReference = typeReferenceFromTypeBinding(calledTypeBindingRaw);

        Expression exprGetCallingClassInstance = (currentScope.methodScope().isStatic || currentScope.methodScope().isConstructorCall) ?
                new NullLiteral(0,0) :
                new ThisReference(0, 0);//new QualifiedThisReference(callingTypeReference,0,0)
        //note: cannot use 'this' inside call to another constructor

        Expression exprGetCalledClassInstance = messageSend.binding.isStatic()?
                new CastExpression(new NullLiteral(0,0), calledTypeReference):
                fixReceiverIfThisAndTypeMismatches(messageSend.receiver, calledTypeBinding, calledTypeReference);

        //(forcing Qualified, e.g. X.this.fn() for inner types since simple fn() call will result in ThisReference poimnitng to inner class and MessageSend magically fixes this
        //in its actualReceiverType)

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

    /**
     * fixes situation when there was originally a this reference, but moving to field requires nested type qualifier
     * @param receiver
     * @param calledTypeBinding
     * @param calledTypeReference
     * @return
     */
    static Expression fixReceiverIfThisAndTypeMismatches(Expression receiver, TypeBinding calledTypeBinding, TypeReference calledTypeReference) {
        return (receiver instanceof ThisReference &&
                !receiver.resolvedType.isSubtypeOf(calledTypeBinding) &&
                receiver.resolvedType.id != calledTypeBinding.id)?
                new QualifiedThisReference(calledTypeReference,0,0) :
                receiver;
    }

    /**
     * a static call can be without explicit class name specification, if called class is current class
     * in the original call receiver would be ThisReference, but this can only work where used, not when lifted into a field
     * this code returns receiver in the form Class.call
     * @param receiver
     * @param calledTypeReference
     * @return
     */
    static Expression fixReceiverIfUnmarkedStaticCall(Expression receiver, TypeReference calledTypeReference) {
        return (receiver instanceof ThisReference)?
                typeReferenceToNameReference(calledTypeReference, receiver) :
                receiver;
    }

    static Expression typeReferenceToNameReference(TypeReference typeReference, Expression defaultValue) {
        if (typeReference instanceof QualifiedTypeReference){
            QualifiedTypeReference qualifiedTypeReference = (QualifiedTypeReference) typeReference;
            return new QualifiedNameReference(qualifiedTypeReference.tokens, new long[qualifiedTypeReference.tokens.length], 0, 0);
        }
        if (typeReference instanceof SingleTypeReference){
            SingleTypeReference singleTypeReference = (SingleTypeReference) typeReference;
            return new SingleNameReference(singleTypeReference.token, 0);
        }
        return defaultValue;
    }

    static AllocationExpression makeCallSiteExpression(AllocationExpression messageSend, BlockScope currentScope, TypeBinding calledTypeBinding) {
        LookupEnvironment lookupEnvironment = currentScope.environment();

        AllocationExpression allocationExpression = new AllocationExpression();

        TypeBinding callingTypeBindingForDescription = convertIfAnonymous(currentScope.classScope().referenceContext.binding);

        TypeBinding calledTypeBindingForDescription = messageSend.resolvedType;

        TypeBinding calledTypeBindingRaw = lookupEnvironment.convertToRawType(calledTypeBinding, false);

        TypeReference calledTypeReference = typeReferenceFromTypeBinding(calledTypeBinding);

        ReferenceBinding callSiteTypeBinging = bindingForCallContextType(
                calledTypeBindingRaw,
                currentScope.environment()
        );

        TypeReference callSiteType = typeReferenceFromTypeBinding(callSiteTypeBinging);

        allocationExpression.type = callSiteType;

        Expression exprGetCallingClass = new StringLiteral(removeLocalPrefix(callingTypeBindingForDescription.readableName()), 0,0,0);

        Expression exprGetCalledClass = new StringLiteral(removeLocalPrefix(calledTypeBindingForDescription.readableName()), 0,0,0);

        Expression exprGetCallingClassInstance = (currentScope.methodScope().isStatic || currentScope.methodScope().isConstructorCall)?
                new NullLiteral(0,0) :
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
            TypeBinding calledTypeBinding = ((ParameterizedTypeBinding) ((ParameterizedTypeBinding) messageToFieldApply.actualReceiverType).arguments[0]).arguments[0];

            callSiteExpression = makeCallSiteExpression(allocationExpression, currentScope, calledTypeBinding);
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

    static public void testabilityInstrumentationError(Scope currentScope, String message, Exception ex, boolean throwAbort) {
        currentScope.problemReporter().testabilityInstrumentationError(
                TESTABLEJAVA_INTERNAL_ERROR + ": " + message,
                ex,
                throwAbort);
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
    static public void testabilityInstrumentationError(Scope currentScope, String message, boolean throwAbort) {
        currentScope.problemReporter().testabilityInstrumentationError(
                TESTABLEJAVA_INTERNAL_ERROR + ": " + message, throwAbort);
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
    static QualifiedNameReference makeQualifiedNameReference(String [] sPath) {
        char[][] path = Arrays.stream(sPath).map(String::toCharArray).collect(toList()).toArray(new char[0][]);

        return new QualifiedNameReference(path, new long[path.length], 0, 0);
    }
    static SingleNameReference makeSingleNameReference(String targetFieldNameInThis) {
        return new SingleNameReference(targetFieldNameInThis.toCharArray(), 0);
    }
    static SingleNameReference makeSingleNameReference(char[] targetFieldNameInThis) {
        return new SingleNameReference(targetFieldNameInThis, 0);
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


    public static List<FieldDeclaration> makeFields(
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

                    ret.addAll(makeListenerFields(typeDeclaration.compilationResult, typeDeclaration.binding, referenceBinding, lookupEnvironment));

                    //find all local type declarations and add corresponding fields
                    List<TypeDeclaration> localTypeDeclarations = findLocalTypeDeclarations(typeDeclaration, null);
                    List<TypeDeclaration> memberTypeDeclarations = typeDeclaration.memberTypes==null?
                            Collections.emptyList() :
                            Arrays.asList(typeDeclaration.memberTypes);

                    List<TypeDeclaration> localAndMemberTypeDeclarations = new ArrayList<>(localTypeDeclarations);
                    localAndMemberTypeDeclarations.addAll(memberTypeDeclarations);

                    if (localAndMemberTypeDeclarations.stream().anyMatch(t -> t.binding.isEnum())){
                        //note: a single enum can generate multiple local types, see testTestabilityInjectFunctionField_NotInstrumentingInsideEnum_Realistic
                        Testability.testabilityInstrumentationWarning(referenceBinding.scope,
                                "cannot add listener inside enum: " + new String(typeDeclaration.name));
                    }

                    localAndMemberTypeDeclarations.stream().
                            map(t -> t.binding).
                            filter(b -> !b.isEnum()).
                            forEach(typeBinding -> {
                                ret.addAll(makeListenerFields(typeDeclaration.compilationResult, typeBinding, referenceBinding, lookupEnvironment));
                            });

                }

            }

            if (instrumentationOptions.contains(InstrumentationOptions.INSERT_REDIRECTORS)) {

                try {
                    List<FieldDeclaration> redirectorFields = makeRedirectorFields(
                            typeDeclaration,
                            referenceBinding,
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
                    //TODO reen
//                    map(fieldDeclaration -> {
//                        try {
//                            fieldDeclaration.type.resolveType(typeDeclaration.initializerScope); //TODO experz
//                            fieldDeclaration.resolve(typeDeclaration.initializerScope);
//
//                            if (!validateMessageSendsAndTypesInCode(fieldDeclaration, typeDeclaration.initializerScope)) {
//                                Testability.testabilityInstrumentationWarning(
//                                        typeDeclaration.initializerScope,
//                                        "The field cannot be validated, and will not be injected: " + new String(fieldDeclaration.name)
//                                );
//                                return null;
//                            }
//
//                            UnconditionalFlowInfo flowInfo = FlowInfo.initial(0);
//                            FlowContext flowContext = null;
//                            InitializationFlowContext staticInitializerContext = new InitializationFlowContext(null,
//                                    typeDeclaration,
//                                    flowInfo,
//                                    flowContext,
//                                    typeDeclaration.staticInitializerScope);
//
//                            fieldDeclaration.analyseCode(typeDeclaration.staticInitializerScope, staticInitializerContext, flowInfo);
//
//                            return fieldDeclaration;
//                        } catch (Exception ex) {
//                            testabilityInstrumentationError(
//                                    typeDeclaration.scope,
//                                    "The field cannot be resolved: " + new String(fieldDeclaration.name),
//                                    ex);
//
//                            return null;
//                        }
//                    }).
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
     * @return if it is generic type (not instance)
     * note: there are empirical cases where type shows as parameterized, but its arguments are type variables, which we consider generic type
     */
    static boolean isGenericType(TypeBinding typeBinding) {
        return !typeBinding.isParameterizedType() && typeBinding.typeVariables() != null && typeBinding.typeVariables().length > 0 ||
                typeBinding.isParameterizedType() && Arrays.stream(((ParameterizedTypeBinding)typeBinding).arguments).anyMatch(TypeVariableBinding.class::isInstance);
    }

    static List<TypeDeclaration> findLocalTypeDeclarations(TypeDeclaration typeDeclaration, ClassScope classScope) {

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


    static public boolean validateMessageSendsAndTypesInCode(FieldDeclaration fieldDeclaration, MethodScope scope) {

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
                @Override
                public boolean visit(SingleTypeReference t, BlockScope scope) {
                    if (t.resolvedType instanceof ProblemReferenceBinding)
                        throw exceptionVisitorInterrupted;
                    return true;
                }
                @Override
                public boolean visit(ParameterizedSingleTypeReference t, BlockScope scope) {
                    if (t.resolvedType instanceof ProblemReferenceBinding)
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

    public static List<FieldDeclaration> makeListenerFields(
            CompilationResult compilationResult,
            ReferenceBinding typeBinding,
            SourceTypeBinding referenceBinding,
            LookupEnvironment lookupEnvironment) {

        if (typeBinding.isEnum()) {
            return Collections.emptyList();
        }

        ReferenceBinding typeBindingRaw = (ReferenceBinding) convertToRawIfGeneric(typeBinding, lookupEnvironment);
         //note: either passed type, which is ReferenceBinding, or RawTypeBinding, which is also ReferenceBinding

        FieldDeclaration fieldDeclarationPreCreate = makeListenerFieldDeclaration(
                compilationResult,
                typeBindingRaw,
                referenceBinding,
                makeListenerFieldName(typeBinding, "preCreate"));//"$$"+supertypeName+"preCreate");

        FieldDeclaration fieldDeclarationPostCreate = makeListenerFieldDeclaration(
                compilationResult,
                typeBindingRaw,
                referenceBinding,
                makeListenerFieldName(typeBinding, "postCreate"));//"$$"+supertypeName+"postCreate");

        ArrayList<FieldDeclaration> ret = new ArrayList<>();

        ret.add(fieldDeclarationPreCreate);
        ret.add(fieldDeclarationPostCreate);

        return ret;
    }

    /**
     *
     * @param typeDeclaration
     * @param referenceBinding
     * @return unique field instances
     */
    public static List<FieldDeclaration> makeRedirectorFields(
            TypeDeclaration typeDeclaration,
            SourceTypeBinding referenceBinding,
            Consumer<Map<Expression, FieldDeclaration>> originalCallToFieldProducer) throws Exception {

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

    static String printExpr(Expression expression, CompilationResult unitResult) {
        int[] lineEnds = unitResult.getLineSeparatorPositions();
        int lineNumber = org.eclipse.jdt.internal.compiler.util.Util.getLineNumber(expression.sourceStart, lineEnds , 0, lineEnds.length-1);
        return "@" + lineNumber + ": " + expression;
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
            Argument argument = new Argument((" arg" + i).toCharArray(), 0, null, 0, true);
            lambdaArguments[i] = argument;
        }

        lambdaExpression.setArguments(lambdaArguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

//TODO reen or allways null?        lambdaExpression.setExpectedType(typeReferenceForFunction.resolvedType);

        return lambdaExpression;
    }

    static ReferenceBinding bindingForCallContextType(
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
                new TypeBinding[]{calledType},
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

//        callingType = convertToRawIfGeneric(callingType, lookupEnvironment);

        TypeBinding calledType = convertIfAnonymous(receiverResolvedType);

//        calledType = convertToRawIfGeneric(calledType, lookupEnvironment);

        ParameterizedTypeBinding contextArgument = (ParameterizedTypeBinding) bindingForCallContextType(//TODO fix type of bindingForCallContextType
                calledType, //this should be apparent compile type called
                lookupEnvironment);

        typeArgumentsForFunction[iArg++] = contextArgument;

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

        typeArgumentsForFunction = convertToObjectIfTypeVariables(lookupEnvironment, typeArgumentsForFunction);

        typeArgumentsForFunction = convertToParentsIfAnonymousInnerClasses(typeArgumentsForFunction);

        //TODO test! see testTestabilityInjectFunctionField_getClass
        //when disabled: Lambda expression's parameter  arg0 is expected to be of type CallContext<X,Class<capture#2-of ?>>
        for (int iTypeArg=0; iTypeArg<typeArgumentsForFunction.length; iTypeArg++){
            TypeBinding typeBinding = typeArgumentsForFunction[iTypeArg];

            typeArgumentsForFunction[iTypeArg] = convertCaptureBinding(typeBinding);

        }
        //for every argument except CallContext, if it is generic, convert to raw
        for (int iTypeArg=1; iTypeArg<typeArgumentsForFunction.length; iTypeArg++) {
            if (deepHasTypeVariables(typeArgumentsForFunction[iTypeArg])) //TODO or any parameterized type with type arg variable?
                typeArgumentsForFunction[iTypeArg] =
                        lookupEnvironment.convertToRawType(typeArgumentsForFunction[iTypeArg], false);
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


        //TODO other place
        List<TypeReference> argCastTypeReferences = new ArrayList<>(); //null if no cast needed
        for (int i = 0, length = originalMessageSend.arguments == null? 0: originalMessageSend.arguments.length; i < length; i++) {
            Expression argument = originalMessageSend.arguments[i];

            TypeReference castType = null;

            if (argument instanceof NameReference &&
                    argument.resolvedType instanceof TypeVariableBinding /*hasTypeVariables(argument.resolvedType*/) {
                NameReference nameReference = (NameReference) argument;
                Binding binding = nameReference.binding;
                castType = ((LocalVariableBinding) binding).declaration.type;
            }
            argCastTypeReferences.add(castType);
        }

        int additionalTypeVarCountForMethod = (int) argCastTypeReferences.stream().filter(Objects::nonNull).count();

//                originalMessageSend.arguments==null? //TODO other place
//                0 :
//                (int) Arrays.stream(originalMessageSend.arguments).
//                        map(arg -> arg.resolvedType).
//                        filter(TypeVariableBinding.class::isInstance).
//                        count();



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

        ReferenceBinding classThatWillContainField = typeDeclaration.binding.outermostEnclosingType();

        FieldBinding fieldBinding = new FieldBinding(
                fieldDeclaration,
                null, //typeBindingForFunction,
                fieldDeclaration.modifiers,
                classThatWillContainField);

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

        boolean isStaticCall = originalMessageSend.binding.isStatic();
        {
            if (isStaticCall){
                messageSendInLambdaBody.receiver =
                        fixReceiverIfUnmarkedStaticCall(
                                originalMessageSend.receiver,
                                typeReferenceFromTypeBinding(calledType)
                        );
            } else {
                char[][] receiverInstanceCall = {" arg0".toCharArray(), "calledClassInstance".toCharArray()};

                Expression newReceiverInstanceCall =
                        new QualifiedNameReference(receiverInstanceCall, new long[receiverInstanceCall.length], 0, 0);

                messageSendInLambdaBody.receiver = newReceiverInstanceCall;
            }
        }
        boolean needsReflectiveCall =
                !originalMessageSend.binding.canBeSeenBy( //method used in original message send is visible from new field
                        messageSendInLambdaBody,
                        ((SourceTypeBinding) classThatWillContainField).scope
                );

        Expression reflectiveInstanceCallInLambdaBody = null;

        if (needsReflectiveCall) {
            //e.g:
            // new testability.ReflectiveCaller(String.class, "split", String.class, int.class).
            //  apply("a,b,c", ",", 2);

            Expression calledClassExpression = isStaticCall?
                    new ClassLiteralAccess(0, typeReferenceFromTypeBinding(originalMessageSend.actualReceiverType)) :
                    new MessageSendBuilder("getClass").
                            receiver(" arg0", "calledClassInstance").
                            build().
                            orElseThrow(()->new RuntimeException("internal error"));

            Expression calledMethodNameExpression = new StringLiteral(originalMessageSend.selector, 0, 0, 0);

            Expression[] calledMethodArgTypesExpressions = Arrays.stream(originalBindingParameters).
                    map(typeBinding -> new ClassLiteralAccess(0, typeReferenceFromTypeBinding(typeBinding))).
                    collect(toList()).
                    toArray(new Expression[originalBindingParameters.length]);

            AllocationExpression newReflectiveCallerExpression = new AllocationExpressionBuilder().
                    type("testablejava", "ReflectiveCaller").
                    arg(calledClassExpression).
                    arg(calledMethodNameExpression).
                    args(calledMethodArgTypesExpressions).
                    build(lookupEnvironment).
                    orElseThrow(()->new RuntimeException("internal error"));

            String[] argStrings = IntStream.range(1, originalArguments.length + 1).
                    mapToObj(iArgN -> " arg" + iArgN).
                    collect(toList()).
                    toArray(new String[]{});

            MessageSendBuilder reflectiveMessageSendInLambdaBodyBuilder =
                new MessageSendBuilder("apply").
                        receiver(newReflectiveCallerExpression);

            if (isStaticCall)
                reflectiveMessageSendInLambdaBodyBuilder = reflectiveMessageSendInLambdaBodyBuilder.argNullLiteral();
            else
                reflectiveMessageSendInLambdaBodyBuilder = reflectiveMessageSendInLambdaBodyBuilder.argQualifiedNameReference(" arg0", "calledClassInstance");

            MessageSend reflectiveMessageSendInLambdaBody =
                    reflectiveMessageSendInLambdaBodyBuilder.
                        argSingleNameReferences(argStrings).
                        build().
                        orElseThrow(()->new RuntimeException("internal error"));

            reflectiveInstanceCallInLambdaBody = returnsVoid?
                    reflectiveMessageSendInLambdaBody :
                    new CastExpression(
                            reflectiveMessageSendInLambdaBody,
                            typeReferenceFromTypeBinding(boxIfApplicable(fieldTypeBinding, lookupEnvironment))
                    );
        }

        messageSendInLambdaBody.binding = null;//this is to resolve expression without apparent receiver

        //arguments need to be wired directly to lambda arguments, cause they can be constants, etc

        int methodSendInLambdaBodyArgCount = typeArgumentsForFunction.length - 1 - (returnsVoid ? 0 : 1);

        Expression[] argv = new Expression[methodSendInLambdaBodyArgCount];

        for (int i = 0, length = argv.length; i < length; i++) {
            char[] name = (" arg" + (i + 1)).toCharArray();

            SingleNameReference singleNameReference = new SingleNameReference(name, 0);

            singleNameReference.setExpressionContext(originalMessageSend.expressionContext);

            if (argCastTypeReferences.get(i) != null){

                TypeReference typeReference = argCastTypeReferences.get(i);

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

        boolean methodCanThrow = methodCanThrow(originalMessageSend);

        Block block = makeStatementBlockForCallingOriginalMethod(
                returnsVoid,
                reflectiveInstanceCallInLambdaBody == null? messageSendInLambdaBody : reflectiveInstanceCallInLambdaBody,
                methodCanThrow);

        lambdaExpression.setBody(block);

        boolean typeVariablesInMethodArgs = additionalTypeVarCountForMethod > 0;


        if (!typeVariablesInMethodArgs && !needsReflectiveCall) {
            fieldDeclaration.initialization = lambdaExpression;
        } else {
            //anonymous type instead of lambda, since method has type variables
            TypeDeclaration anonymousType = new TypeDeclaration(typeDeclaration.compilationResult);

            if (anonymousType.methods == null)
                anonymousType.methods = new MethodDeclaration[]{};

            anonymousType.methods = Arrays.copyOf(anonymousType.methods, anonymousType.methods.length + 1);
            MethodDeclaration methodDeclaration = new MethodDeclaration(typeDeclaration.compilationResult);

            anonymousType.methods[anonymousType.methods.length - 1] = methodDeclaration;

            methodDeclaration.binding = typeBindingForFunction.getSingleAbstractMethod(typeDeclaration.scope, true);
            methodDeclaration.binding.modifiers &= ~ClassFileConstants.AccAbstract;//clear flag
            methodDeclaration.binding.modifiers |= ClassFileConstants.AccPublic;
            methodDeclaration.selector = methodDeclaration.binding.selector;
            methodDeclaration.modifiers = methodDeclaration.binding.modifiers;

            int argc = returnsVoid?
                    typeArgumentsForFunction.length :
                    typeArgumentsForFunction.length - 1;

            Argument[] anonInitializerArguments = new Argument[argc];
            for (int i = 0; i < argc; i++) { //type args has return at the end, method args do not
                TypeBinding typeBindingForArg = boxIfApplicable(typeBindingForFunction.arguments[i], lookupEnvironment);
                typeBindingForArg = convertToRawIfGeneric(typeBindingForArg, lookupEnvironment);
                TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
                Argument argument = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
                anonInitializerArguments[i] = argument;
            }

            methodDeclaration.arguments = anonInitializerArguments;
            methodDeclaration.returnType = typeReferenceFromTypeBinding(methodDeclaration.binding.returnType);
            methodDeclaration.statements = block.statements;

            if (!argCastTypeReferences.isEmpty()) {
                //detect all type variables
                List<TypeParameter> typeArgs = argCastTypeReferences.stream().
                        filter(Objects::nonNull).
                        flatMap(typeReference -> recursiveTypeReferences(typeReference)).
                        map(typeReferenceNode -> typeReferenceNode.resolvedType).
                        filter(TypeVariableBinding.class::isInstance).
                        map(TypeVariableBinding.class::cast).
                        map(ReferenceBinding::sourceName).
                        distinct().
                        map(name -> {
                            TypeParameter ret = new TypeParameter();
                            ret.name = name;
                            return ret;
                        }).

                        collect(toList()); //note: distinct needed since no hashcode defined for TypeParameter

                if (!typeArgs.isEmpty())
                    methodDeclaration.typeParameters = typeArgs.toArray(new TypeParameter[0]);

            }
            anonymousType.name = CharOperation.NO_CHAR;
            anonymousType.bits |= (ASTNode.IsAnonymousType | ASTNode.IsLocalType);
            QualifiedAllocationExpression alloc = new QualifiedAllocationExpression(anonymousType);

            alloc.type = typeReferenceFromTypeBinding(typeBindingForFunction);

            fieldDeclaration.initialization = alloc;
        }
        return fieldDeclaration;
    }

    static boolean deepHasTypeVariables(TypeBinding typeBinding) {
        if (typeBinding instanceof TypeVariableBinding)
            return true;
        if (!(typeBinding instanceof ParameterizedTypeBinding))
            return false;

        return
                arrayStreamOfNullable(((ParameterizedTypeBinding) typeBinding).arguments).
                        filter(Objects::nonNull).
                        map(Testability::deepHasTypeVariables).
                        anyMatch(b -> b);
    }

    static Stream<TypeReference> recursiveTypeReferences(TypeReference typeReference) {
        return Stream.concat(
                Stream.of(typeReference),
                arrayStreamOfNullable(typeReference.getTypeArguments()).
                        filter(Objects::nonNull).
                        flatMap(typeReferenceArray -> Arrays.stream(typeReferenceArray)).
                        flatMap(Testability::recursiveTypeReferences)
        );

    }

    public static <T> Stream<T> arrayStreamOfNullable(T[] array) {
        return array == null? Stream.empty() : Arrays.stream(array);
    }

    static boolean hasTypeVariables(TypeBinding typeBinding) {
        if (typeBinding instanceof TypeVariableBinding)
            return true;
        if (typeBinding instanceof ParameterizedTypeBinding)
            return true; //type parameters will qualify
        return false;
    }

    static boolean methodCanThrow(MessageSend messageSend) {
        return methodCanThrow(messageSend.binding);
    }

    static boolean methodCanThrow(AllocationExpression allocationExpression) {
        return methodCanThrow(allocationExpression.binding);
    }

    static boolean methodCanThrow(MethodBinding binding) {
        return binding.thrownExceptions != null &&
                binding.thrownExceptions.length > 0;
    }

    static Block makeStatementBlockForCallingOriginalMethod(
            boolean returnsVoid,
            Expression messageSendInLambdaBody,
            boolean methodCanThrow
            ) {

        Expression messageSendExpression = messageSendInLambdaBody;

        Block block = new Block(2);

        LabeledStatement labeledStatement = new LabeledStatement(
                TESTABILITYLABEL.toCharArray(),
                new EmptyStatement(0, 0),
                0, 0);

        if (!methodCanThrow) {

            if (returnsVoid) {
                ReturnStatement returnStatement = new ReturnStatement(null, 0, 0, true);

                block.statements = new Statement[]{
                        labeledStatement,
                        messageSendInLambdaBody,
                        returnStatement
                };

            } else {

                ReturnStatement returnStatement = new ReturnStatement(messageSendExpression, 0, 0);

                block.statements = new Statement[]{
                        labeledStatement,
                        returnStatement
                };
            }
            return block;
        } else {

            char[][] exceptionPath = new char[][]{
                    "java".toCharArray(),
                    "lang".toCharArray(),
                    "Throwable".toCharArray()};

            Argument catchArgument = new Argument(
                    "ex".toCharArray(),
                    0,
                    new QualifiedTypeReference(exceptionPath, new long[exceptionPath.length]),
                    0);

            MessageSend uncheckedThrowStatement = new MessageSend();//testablejava.Helpers.uncheckedThrow(ex);

            uncheckedThrowStatement.receiver =
                    makeQualifiedNameReference(
                            new String[]{"testablejava", "Helpers"}
                    );

            uncheckedThrowStatement.selector = "uncheckedThrow".toCharArray();

            uncheckedThrowStatement.arguments = new Expression[]{
                    makeSingleNameReference("ex")};

            TryStatement tryStatement = new TryStatement();

            Block tryBlock = new Block(2);
            tryStatement.tryBlock = tryBlock;

            Block catchBlock = new Block(2);
            catchBlock.statements = new Statement[]{uncheckedThrowStatement};
            Block[] catchBlocks = new Block[]{catchBlock};
            tryStatement.catchBlocks = catchBlocks;

            Argument[] catchArguments = new Argument[]{catchArgument};
            tryStatement.catchArguments = catchArguments;

            if (returnsVoid) {
//            try {
//              ...
//            } catch (Exception ex) {
//                testablejava.Helpers.uncheckedThrow(ex);
//            }

                tryBlock.statements = new Statement[]{messageSendExpression};

                ReturnStatement returnStatement = new ReturnStatement(null, 0, 0, true);

                block.statements = new Statement[]{
                        labeledStatement,
                        tryStatement,//messageSendInLambdaBody,
                        returnStatement
                };

            } else {
//            try {
//                return ...
//            } catch (Exception ex) {
//                testablejava.Helpers.uncheckedThrow(ex);
//            }
//            return null;

                ReturnStatement returnStatement = new ReturnStatement(messageSendExpression, 0, 0);

                tryBlock.statements = new Statement[]{returnStatement};

                ReturnStatement dummyReturnStatament = new ReturnStatement(new NullLiteral(0, 0), 0, 0);

                block.statements = new Statement[]{
                        labeledStatement,
                        tryStatement,//returnStatement
                        dummyReturnStatament
                };
            }
            return block;
        }
    }

    static TypeBinding[] convertToObjectIfTypeVariables(LookupEnvironment lookupEnvironment, TypeBinding[] typeArgumentsForFunction) {
        //replace references to type arguments with Object type
        ReferenceBinding objectTypeBinding = lookupEnvironment.askForType(new char[][]{"java".toCharArray(), "lang".toCharArray(), "Object".toCharArray()});
        return Arrays.stream(typeArgumentsForFunction).
                map(typeBinding -> typeBinding instanceof TypeVariableBinding ? objectTypeBinding : typeBinding).
                collect(toList()).
                toArray(new TypeBinding[typeArgumentsForFunction.length]);
    }


    static TypeBinding[] convertToParentsIfAnonymousInnerClasses(TypeBinding[] typeArgumentsForFunction) {
        //rewire anonymous inner classes to their parents
        return Arrays.stream(typeArgumentsForFunction).
                map(Testability::convertIfAnonymous).
                collect(toList()).
                toArray(new TypeBinding[typeArgumentsForFunction.length]);
    }

    public static TypeBinding convertToRawIfGeneric(TypeBinding callingType, LookupEnvironment lookupEnvironment) {
        if (isGenericType(callingType))
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
            TypeBinding bound = ((CaptureBinding) typeBinding).wildcard.bound;//TODO will superclass always work instead?
            if (bound == null){
                return ((CaptureBinding) typeBinding).superclass;
            }
            return bound;
//            return typeBinding.clone(typeBinding.enclosingType());
        }
        if (typeBinding instanceof ParameterizedTypeBinding && !typeBinding.isRawType()) {
            ParameterizedTypeBinding typeBindingParameterized =
                    (ParameterizedTypeBinding) typeBinding;
            TypeBinding[] originalArgs = typeBindingParameterized.arguments;
            if (originalArgs != null) {
                TypeBinding[] newArgs = Arrays.stream(originalArgs).
                        map(arg -> convertCaptureBinding(arg)).
                        collect(toList()).
                        toArray(new TypeBinding[originalArgs.length]); //TODO this is partially in-place conversion, partially new return

                typeBindingParameterized.arguments = newArgs;
            }

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

        if (originalMessageSend instanceof QualifiedAllocationExpression) {
            Testability.testabilityInstrumentationWarning(referenceBinding.scope, "cannot redirect anonymous class allocation: " + originalMessageSend);
            return null;
        }

        if (typeDeclarationContainingCall.binding.isEnum()) {
            Testability.testabilityInstrumentationWarning(referenceBinding.scope, "cannot redirect inside enum: " + new String(typeDeclarationContainingCall.name));
            return null;
        }

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

        TypeBinding calledType = convertIfLocal(originalMessageSend.resolvedType);

        typeArguments[0] = bindingForCallContextType(
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
        typeArguments = convertToObjectIfTypeVariables(lookupEnvironment, typeArguments);

        //rewire anonymous inner classes to their parents
        typeArguments = convertToParentsIfAnonymousInnerClasses(typeArguments);

        int functionArgCount = typeArguments.length - 1;

        int additionalTypeVarCountForMethod = originalMessageSend.arguments==null? //TODO other place
                0 :
                (int) Arrays.stream(originalMessageSend.arguments).
                        map(arg -> arg.resolvedType).
                        filter(TypeVariableBinding.class::isInstance).
                        count();

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
            arguments[i] = new Argument((" arg" + i).toCharArray(), 0, null, 0, true);
        }

        lambdaExpression.setArguments(arguments);

        lambdaExpression.setExpressionContext(originalMessageSend.expressionContext);

        lambdaExpression.setExpectedType(fieldDeclaration.type.resolvedType);

        AllocationExpression messageSendInLambdaBody;

        if ((originalMessageSend.resolvedType.isMemberType() && !originalMessageSend.resolvedType.isStatic()) ||
                originalMessageSend.resolvedType.isLocalType()){
            //explicit arg0.callingClassInstance.new MemberType()
            //note: except for member types tagged 'static'

            QualifiedAllocationExpression inLambdaBodyQualifiedAllocationExpression =
                    new QualifiedAllocationExpression();

            char[][] receiverPathDynamicCall = { " arg0".toCharArray(), "callingClassInstance".toCharArray()};

            Expression enclosingInstanceCall =
                    new QualifiedNameReference(receiverPathDynamicCall, new long[receiverPathDynamicCall.length], 0, 0);

            //since callingClassInstance is an object, we need to cast to enclosing instance type. There will be a unique type per call

            TypeReference typeReferenceContainingCall = typeReferenceFromTypeBinding(typeDeclarationContainingCall.binding);

            CastExpression castExpression = new CastExpression(enclosingInstanceCall, typeReferenceContainingCall);

            inLambdaBodyQualifiedAllocationExpression.enclosingInstance = castExpression;
            messageSendInLambdaBody = inLambdaBodyQualifiedAllocationExpression;
        } else {
            messageSendInLambdaBody = new AllocationExpression();
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




//        Block block = new Block(2);
//        LabeledStatement labeledStatement = new LabeledStatement(
//                TESTABILITYLABEL.toCharArray(),
//                new EmptyStatement(0, 0),
//                0, 0);
//
//        ReturnStatement returnStatement = new ReturnStatement(messageSendInLambdaBody, 0, 0);
//
//        block.statements = new Statement[]{
//                labeledStatement,
//                returnStatement
//        };
//


        boolean methodCanThrow = methodCanThrow(originalMessageSend);

        Block block = makeStatementBlockForCallingOriginalMethod(false, messageSendInLambdaBody, methodCanThrow);

        lambdaExpression.setBody(block);

        boolean typeVariablesInMethodArgs = additionalTypeVarCountForMethod > 0;

        if (!typeVariablesInMethodArgs){ //!(originalMessageSend.binding instanceof ParameterizedGenericMethodBinding)) {
            fieldDeclaration.initialization = lambdaExpression;
        } else {
            TypeDeclaration anonymousType = new TypeDeclaration(typeDeclaration.compilationResult);

            if (anonymousType.methods == null)
                anonymousType.methods = new MethodDeclaration[]{};
            anonymousType.methods = Arrays.copyOf(anonymousType.methods, anonymousType.methods.length + 1);
            MethodDeclaration methodDeclaration = new MethodDeclaration(typeDeclaration.compilationResult);

            anonymousType.methods[anonymousType.methods.length - 1] = methodDeclaration;

            methodDeclaration.binding = typeBinding.getSingleAbstractMethod(typeDeclaration.scope, true);
            methodDeclaration.binding.modifiers &= ~ClassFileConstants.AccAbstract; //unset
            methodDeclaration.binding.modifiers |= ClassFileConstants.AccPublic;
            methodDeclaration.selector = methodDeclaration.binding.selector;
            methodDeclaration.modifiers = methodDeclaration.binding.modifiers;

            Argument[] anonInitializerArguments = new Argument[argc];
            for (int i = 0; i < argc; i++) {
                TypeBinding typeBindingForArg = boxIfApplicable(typeBinding.arguments[i], lookupEnvironment);
                TypeReference typeReference = typeReferenceFromTypeBinding(typeBindingForArg);
                anonInitializerArguments[i] = new Argument((" arg" + i).toCharArray(), 0, typeReference, 0);
            }

            methodDeclaration.arguments = anonInitializerArguments;
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
            anonymousType.bits |= (ASTNode.IsAnonymousType | ASTNode.IsLocalType);

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

        Argument argument = new Argument((" arg" + 0).toCharArray(), 0, null, 0, true);

        arguments[0] = argument;

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
                SingleTypeReference objectTypeReference = new SingleTypeReference("Object".toCharArray(), 0);//TODO full p

                return objectTypeReference;
            }
            if (typeBinding instanceof ReferenceBinding) {
                ReferenceBinding binaryTypeBinding = (ReferenceBinding) typeBinding;
                if (typeBinding instanceof WildcardBinding) {
                    WildcardBinding wildcardBinding = (WildcardBinding) typeBinding;
                    Wildcard wildcard = new Wildcard(wildcardBinding.boundKind);
                    if (wildcardBinding.bound != null)
                        wildcard.bound = typeReferenceFromTypeBinding(wildcardBinding.bound);
                    return wildcard;
                }

//                if (binaryTypeBinding instanceof CaptureBinding){ //TODO causing type mismatch
//                    return typeReferenceFromTypeBinding(((CaptureBinding) binaryTypeBinding).sourceType);
//                }

                char[][] compoundName = binaryTypeBinding.compoundName!=null?
                        expandInternalName(removeLocalPrefix(binaryTypeBinding.compoundName)) :
                        new char[][]{binaryTypeBinding.sourceName()}
                        ;
                if (binaryTypeBinding.compoundName == null && !(binaryTypeBinding instanceof TypeVariableBinding))
                    Testability.testabilityInstrumentationWarning(null,"binding has null compound name: " + binaryTypeBinding.getClass().getName());

                if (typeBinding.isParameterizedType() && !typeBinding.isEnum()) { //note: enums derived from Enum<Type>, but we do not want to add type parms to them
                    ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;
                    TypeReference[] typeArguments;
                    if (parameterizedTypeBinding.arguments == null)
                        typeArguments = new TypeReference[0];
                    else {
                        typeArguments = new TypeReference[parameterizedTypeBinding.arguments.length];

                        Arrays.stream(parameterizedTypeBinding.arguments).
                                map(Testability::typeReferenceFromTypeBinding).
                                collect(Collectors.toList()).toArray(typeArguments);
                    }

                    TypeReference[][] typeArgumentsCompound = new TypeReference[compoundName.length][];//{typeArguments};
                    typeArgumentsCompound[typeArgumentsCompound.length - 1] = typeArguments;

                    return new ParameterizedQualifiedTypeReference(
                            compoundName,
                            typeArgumentsCompound,
                            dim,
                            new long[compoundName.length]
                    );
                } else if (typeBinding.isGenericType()) {

                    TypeReference[] typeArguments = new TypeReference[typeBinding.typeVariables().length];

                    Arrays.stream(typeBinding.typeVariables()).
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

                } else {
                    return new QualifiedTypeReference(compoundName, new long[compoundName.length]);
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
        Statement[] statements = block.statements;

        return haveTestabilityLabel(statements);
    }

    static boolean haveTestabilityLabel(Statement[] statements) {
        if (statements == null)
            return false;
        if (statements.length<1)
            return false;
        if (!(statements[0] instanceof LabeledStatement))
            return false;
        LabeledStatement labeledStatement = (LabeledStatement) statements[0];

        return new String(labeledStatement.label).equals(TESTABILITYLABEL);
    }

    public static boolean isTestabilityFieldInitializerUsingSpecialLabel(TypeDeclaration typeDeclaration) {
        return Arrays.stream(typeDeclaration.methods).anyMatch(method -> haveTestabilityLabel(method.statements));
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
            // for which we will use actualReceiverType
            ReferenceBinding receiverReferenceBinding = null;

            if (receiverBinding instanceof ReferenceBinding &&
                    !(originalMessageSend.receiver instanceof ThisReference)) {
                receiverReferenceBinding = (ReferenceBinding) receiverBinding;
            } else {
                if (originalMessageSend.actualReceiverType instanceof ReferenceBinding){

                    if (originalMessageSend.actualReceiverType instanceof LocalTypeBinding) {
                        LocalTypeBinding actualReceiverTypeLocal = (LocalTypeBinding) originalMessageSend.actualReceiverType;

                        receiverReferenceBinding = actualReceiverTypeLocal.scope.referenceType().binding;
                    } else {
                        LookupEnvironment environment = ((MessageSend) originalCall).actualReceiverType.getPackage().environment;
                        char[][] compoundName = removeLocalPrefix(((ReferenceBinding) originalMessageSend.actualReceiverType).compoundName);
                        receiverReferenceBinding = //binding.declaringClass;
                                environment.getType(compoundName);
                    }
                }
            }
            if (receiverReferenceBinding == null)
                receiverReferenceBinding = binding.declaringClass;//TODO problem, instead of actual this type, is this reachable

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
        return testabilityFieldName(originalCall, false).stream().collect(joining(""));// + new String(binding.signature());
    }

    static char [] readableName(ReferenceBinding binding, boolean shortClassName) { //see ReferenceBinding::readableName
        StringBuffer nameBuffer = new StringBuffer(10);

        if (shortClassName)
            nameBuffer.append(binding.sourceName());
        else {
            if (binding.isMemberType()) {
                nameBuffer.append(CharOperation.concat(binding.enclosingType().readableName(), binding.sourceName(), '.'));
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

        if (typeBinding.isEnum()) { //note: warning logged elsewhere
            return;
        }

        if (constructorDeclaration.statements == null)
            constructorDeclaration.statements = new Statement[]{};

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

        TypeBinding resultingTypeBinding =
                Testability.boxIfApplicable(convertToRawIfGeneric(typeBinding, lookupEnvironment), lookupEnvironment);

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

            char[][] compoundName;
            if (allocation.type == null) {
                TypeBinding invocationTargetType = allocation.invocationTargetType();
                if (invocationTargetType instanceof ReferenceBinding)
                    compoundName = ((ReferenceBinding) invocationTargetType).compoundName;
                else
                    compoundName = Arrays.stream(new String(invocationTargetType.constantPoolName()).split("/")).
                            map(s->s.toCharArray()).collect(toList()).toArray(new char[0][]);
            }
            else
                compoundName = allocation.type.getParameterizedTypeName();

            String fullName =
                    Arrays.stream(compoundName).
                    map(String::new).collect(joining("."));

            typeNamePrefix = escapeTypeArgsInTypeName(fullName);
        } else if (typeBinding.isLocalType() || typeBinding.isMemberType()) {
            typeNamePrefix = escapeTypeArgsInTypeName(
                    new String(
                       removeLocalPrefix(typeBinding.sourceName())));
        }

        return "$$" +
                (typeNamePrefix.isEmpty() ? "" : typeNamePrefix + "$") +
                targetFieldNameSuffix;

    }

    public static boolean codeContainsSyntaxErrors(CompilationResult fullParseUnitResult) {
        return fullParseUnitResult.hasSyntaxError;
    }

}

class MessageSendBuilder {
    private String methodName;
    private Optional<Expression> receiverExpression = Optional.empty();

    private List<Expression> args = new ArrayList<>();

    public MessageSendBuilder(String methodName){
        this.methodName = methodName;
    }

    public MessageSendBuilder receiver(String ...qualifiedNameChunks){
        receiverExpression = Optional.of(Testability.makeQualifiedNameReference(qualifiedNameChunks));
        return this;
    }
    public MessageSendBuilder receiver(Expression receiverExpression){
        this.receiverExpression = Optional.of(receiverExpression);
        return this;
    }
    public MessageSendBuilder argNullLiteral(){
        args.add(new NullLiteral(0, 0));
        return this;
    }
    public MessageSendBuilder argSingleNameReference(Expression arg){
        args.add(arg);
        return this;
    }
    public MessageSendBuilder argQualifiedNameReference(String ...qualifiedNameChunks){
        args.add(Testability.makeQualifiedNameReference(qualifiedNameChunks));
        return this;
    }
    public MessageSendBuilder argSingleNameReference(String argName){
        args.add(makeSingleNameReference(argName));
        return this;
    }
    public MessageSendBuilder argSingleNameReferences(String ...argNames){
        args.addAll(Arrays.stream(argNames).
                map(argName->makeSingleNameReference(argName)).
                collect(toList()));
        return this;
    }

    public Optional<MessageSend> build(){
        Optional<MessageSend> ret = Optional.empty();

        MessageSend messageSend = new MessageSend();

        messageSend.selector = methodName.toCharArray();

        if (!receiverExpression.isPresent())
            return ret;

        messageSend.receiver = receiverExpression.get();

        if (!args.isEmpty()) {
            messageSend.arguments = args.stream().
                    collect(toList()).
                    toArray(new Expression[args.size()]);
        }

        ret = Optional.of(messageSend);

        return ret;
    }

}

class AllocationExpressionBuilder {

    private List<Expression> args = new ArrayList<>();
    private Optional<String[]> pathElements = Optional.empty();

    public AllocationExpressionBuilder arg(Expression arg){
        this.args.add(arg);
        return this;

    }
    public AllocationExpressionBuilder args(Expression ...args){
        this.args.addAll(Arrays.asList(args));
        return this;
    }

    public AllocationExpressionBuilder type(String ...pathElements){
        this.pathElements = Optional.of(pathElements);
        return this;
    }

    public Optional<AllocationExpression> build(LookupEnvironment lookupEnvironment){
        Optional<AllocationExpression> ret = Optional.empty();

        AllocationExpression allocationExpression = new AllocationExpression();

        if (!pathElements.isPresent())
            return ret;

        char[][] path = Arrays.stream(pathElements.get()).
                map(String::toCharArray).
                collect(toList()).
                toArray(new char[0][]);

        ReferenceBinding genericType = lookupEnvironment.getType(path);

        allocationExpression.type = typeReferenceFromTypeBinding(genericType);

        if (!args.isEmpty()) {
            allocationExpression.arguments = args.stream().
                    collect(toList()).
                    toArray(new Expression[args.size()]);
        }

        ret = Optional.of(allocationExpression);

        return ret;
    }

}