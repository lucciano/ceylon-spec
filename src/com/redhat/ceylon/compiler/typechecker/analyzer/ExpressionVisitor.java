package com.redhat.ceylon.compiler.typechecker.analyzer;

import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkAssignable;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkAssignableWithWarning;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkCallable;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkSupertype;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkTypeBelongsToContainingScope;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.getBaseDeclaration;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.getTypeArguments;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.inLanguageModule;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.typeDescription;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.typeNamesAsIntersection;
import static com.redhat.ceylon.compiler.typechecker.model.Util.addToIntersection;
import static com.redhat.ceylon.compiler.typechecker.model.Util.addToUnion;
import static com.redhat.ceylon.compiler.typechecker.model.Util.findMatchingOverloadedClass;
import static com.redhat.ceylon.compiler.typechecker.model.Util.getContainingClassOrInterface;
import static com.redhat.ceylon.compiler.typechecker.model.Util.getOuterClassOrInterface;
import static com.redhat.ceylon.compiler.typechecker.model.Util.intersectionType;
import static com.redhat.ceylon.compiler.typechecker.model.Util.isAbstraction;
import static com.redhat.ceylon.compiler.typechecker.model.Util.isCompletelyVisible;
import static com.redhat.ceylon.compiler.typechecker.model.Util.isTypeUnknown;
import static com.redhat.ceylon.compiler.typechecker.model.Util.producedType;
import static com.redhat.ceylon.compiler.typechecker.model.Util.unionType;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.MISSING_NAME;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.hasUncheckedNulls;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.name;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.FunctionalParameter;
import com.redhat.ceylon.compiler.typechecker.model.Generic;
import com.redhat.ceylon.compiler.typechecker.model.Getter;
import com.redhat.ceylon.compiler.typechecker.model.Interface;
import com.redhat.ceylon.compiler.typechecker.model.IntersectionType;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.NothingType;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedReference;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.ProducedTypedReference;
import com.redhat.ceylon.compiler.typechecker.model.Scope;
import com.redhat.ceylon.compiler.typechecker.model.Setter;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.model.UnknownType;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.model.ValueParameter;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

/**
 * Third and final phase of type analysis.
 * Finally visit all expressions and determine their types.
 * Use type inference to assign types to declarations with
 * the local modifier. Finally, assigns types to the 
 * associated model objects of declarations declared using
 * the local modifier.
 * 
 * @author Gavin King
 *
 */
public class ExpressionVisitor extends Visitor {
    
    private Tree.Type returnType;
    private Tree.Expression switchExpression;
    private Declaration returnDeclaration;
    private boolean defaultArgument;
    private boolean isCondition;
    private boolean dynamic;

    private Unit unit;
    
    @Override public void visit(Tree.CompilationUnit that) {
        unit = that.getUnit();
        super.visit(that);
    }
        
    private Declaration beginReturnDeclaration(Declaration d) {
        Declaration od = returnDeclaration;
        returnDeclaration = d;
        return od;
    }
    
    private void endReturnDeclaration(Declaration od) {
        returnDeclaration = od;
    }
    
    private Tree.Type beginReturnScope(Tree.Type t) {
        Tree.Type ort = returnType;
        returnType = t;
        if (returnType instanceof Tree.FunctionModifier || 
                returnType instanceof Tree.ValueModifier) {
            returnType.setTypeModel( unit.getNothingDeclaration().getType() );
        }
        return ort;
    }
    
    private void endReturnScope(Tree.Type t, TypedDeclaration td) {
        if (returnType instanceof Tree.FunctionModifier || 
                returnType instanceof Tree.ValueModifier) {
            td.setType( returnType.getTypeModel() );
        }
        returnType = t;
    }
    
    @Override public void visit(Tree.DefaultArgument that) {
        defaultArgument=true;
        super.visit(that);
        defaultArgument=false;
    }
    
    @Override public void visit(Tree.FunctionalParameterDeclaration that) {
        super.visit(that);
        FunctionalParameter p = that.getDeclarationModel();
        Tree.DefaultArgument da = that.getDefaultArgument();
        if (p.isDeclaredVoid() && p.isDefaulted()) { 
            Tree.SpecifierExpression se = da.getSpecifierExpression();
            if (se!=null && se.getExpression()!=null &&
                    !isSatementExpression(se.getExpression())) {
                da.addError("functional parameter is declared void so specified expression must be a statement: " +
                        p.getName());
            }
        }
    }
    
    @Override public void visit(Tree.FunctionArgument that) {
        Tree.Expression e = that.getExpression();
        if (e==null) {
            Tree.Type rt = beginReturnScope(that.getType());           
            Declaration od = beginReturnDeclaration(that.getDeclarationModel());
            super.visit(that);
            endReturnDeclaration(od);
            endReturnScope(rt, that.getDeclarationModel());
        }
        else {
            super.visit(that);
            ProducedType t = unit.denotableType(e.getTypeModel());
            that.getDeclarationModel().setType(t);
            //if (that.getType() instanceof Tree.FunctionModifier) {
                that.getType().setTypeModel(t);
            /*}
            else {
                checkAssignable(t, that.getType().getTypeModel(), e, 
                        "expression type must be assignable to specified return type");
            }*/
            if (that.getType() instanceof Tree.VoidModifier &&
                    !isSatementExpression(e)) {
                e.addError("anonymous function is declared void so specified expression must be a statement");
            }
        }
        if (that.getType() instanceof Tree.VoidModifier) {
            ProducedType vt = unit.getAnythingDeclaration().getType();
            that.getDeclarationModel().setType(vt);            
        }
        that.setTypeModel(that.getDeclarationModel()
                .getProducedTypedReference(null, Collections.<ProducedType>emptyList())
                .getFullType());
    }
    
    @Override public void visit(Tree.ExpressionComprehensionClause that) {
        super.visit(that);
        that.setTypeModel(that.getExpression().getTypeModel());
        that.setFirstTypeModel(unit.getNothingDeclaration().getType());
    }
    
    @Override public void visit(Tree.ForComprehensionClause that) {
        super.visit(that);
        that.setPossiblyEmpty(true);
        Tree.ComprehensionClause cc = that.getComprehensionClause();
        if (cc!=null) {
            that.setTypeModel(cc.getTypeModel());
            Tree.ForIterator fi = that.getForIterator();
            if (fi!=null) {
                Tree.SpecifierExpression se = fi.getSpecifierExpression();
                if (se!=null) {
                    Tree.Expression e = se.getExpression();
                    if (e!=null) {
                        ProducedType it = e.getTypeModel();
                        if (it!=null) {
                            ProducedType et = unit.getIteratedType(it);
                            boolean nonemptyIterable = et!=null &&
                                    it.isSubtypeOf(unit.getNonemptyIterableType(et));
                            that.setPossiblyEmpty(!nonemptyIterable || 
                                    cc.getPossiblyEmpty());
                            ProducedType firstType = unionType(unit.getFirstType(it), 
                                    cc.getFirstTypeModel(), unit);
                            that.setFirstTypeModel(firstType);
                        }
                    }
                }
            }
        }
    }
    
    @Override public void visit(Tree.IfComprehensionClause that) {
        super.visit(that);
        that.setPossiblyEmpty(true);
        that.setFirstTypeModel(unit.getNullDeclaration().getType());
        Tree.ComprehensionClause cc = that.getComprehensionClause();
        if (cc!=null) {
            that.setTypeModel(cc.getTypeModel());
        }
    }
    
    @Override
    public void visit(Tree.AnyMethod that) {
        super.visit(that);
        TypedDeclaration td = that.getDeclarationModel();
        for (Tree.ParameterList list: that.getParameterLists()) {
            for (Tree.Parameter tp: list.getParameters()) {
                if (tp!=null) {
                    Parameter p = tp.getDeclarationModel();
                    if (p.getType()!=null && !isCompletelyVisible(td, p.getType())) {
                        tp.getType().addError("type of parameter is not visible everywhere declaration is visible: " 
                                + p.getName());
                    }
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.AnyClass that) {
        super.visit(that);
        Class td = that.getDeclarationModel();
        if (that.getParameterList()!=null) {
            for (Tree.Parameter tp: that.getParameterList().getParameters()) {
                if (tp!=null) {
                    Parameter p = tp.getDeclarationModel();
                    if (p.getType()!=null && !isCompletelyVisible(td, p.getType())) {
                        tp.getType().addError("type of parameter is not visible everywhere declaration is visible: " 
                                + p.getName());
                    }
                }
            }
        }
    }
    
    @Override public void visit(Tree.Variable that) {
        super.visit(that);
        if (that.getSpecifierExpression()!=null) {
            inferType(that, that.getSpecifierExpression());
            if (that.getType()!=null) {
                ProducedType t = that.getType().getTypeModel();
                if (!t.isUnknown()) {
                    checkType(t, that.getSpecifierExpression());
                }
            }
        }
    }
    
    @Override public void visit(Tree.ConditionList that) {
        if (that.getConditions().isEmpty()) {
            that.addError("empty condition list");
        }
        super.visit(that);
    }

    private void initOriginalDeclaration(Tree.Variable that) {
        if (that.getType() instanceof Tree.SyntheticVariable) {
            Tree.BaseMemberExpression bme = (Tree.BaseMemberExpression) that
                    .getSpecifierExpression().getExpression().getTerm();
            ((TypedDeclaration) that.getDeclarationModel())
                .setOriginalDeclaration((TypedDeclaration) bme.getDeclaration());
        }
    }
    
    @Override public void visit(Tree.IsCondition that) {
        //don't recurse to the Variable, since we don't
        //want to check that the specifier expression is
        //assignable to the declared variable type
        //(nor is it possible to infer the variable type)
        isCondition=true;
        that.getType().visit(this);
        isCondition=false;
        Tree.Variable v = that.getVariable();
        ProducedType type = that.getType().getTypeModel();
        if (v!=null) {
//            if (type!=null && !that.getNot()) {
//                v.getType().setTypeModel(type);
//                v.getDeclarationModel().setType(type);
//            }
            //v.getType().visit(this);
            Tree.SpecifierExpression se = v.getSpecifierExpression();
            ProducedType knownType;
            if (se==null) {
                knownType = null;
            }
            else {
                se.visit(this);
                checkReferenceIsNonVariable(v, se);
                /*checkAssignable( se.getExpression().getTypeModel(), 
                        getOptionalType(getObjectDeclaration().getType()), 
                        se.getExpression(), 
                        "expression may not be of void type");*/
                initOriginalDeclaration(v);
                //this is a bit ugly (the parser sends us a SyntheticVariable
                //instead of the real StaticType which it very well knows!)
                Tree.Expression e = se.getExpression();
                knownType = e==null ? null : e.getTypeModel();
                //TODO: what to do here in case of !is
                if (knownType!=null) {
                    String help = " (expression is already of the specified type)";
                    if (that.getNot()) {
                        if (intersectionType(type,knownType, unit).getDeclaration() instanceof NothingType) {
                            that.addError("does not narrow type: intersection of " + type.getProducedTypeName(unit) + 
                                    " and " + knownType.getProducedTypeName(unit) + " is empty" + help);
                        }
                        else if (knownType.isSubtypeOf(type)) {
                            that.addError("tests assignability to Nothing type: " + knownType.getProducedTypeName(unit) + 
                                    " is a subtype of " + type.getProducedTypeName(unit));
                        }
                    } 
                    else {
                        if (knownType.isSubtypeOf(type)) {
                            that.addError("does not narrow type: " + knownType.getProducedTypeName(unit) + 
                                    " is a subtype of " + type.getProducedTypeName(unit) + help);
                        }
                    }
                }
            }
            defaultTypeToAnything(v);
            if (knownType==null) {
                knownType = unit.getAnythingDeclaration().getType(); //or should we use unknown?
            }
            ProducedType it;
            if (that.getNot()) {
                //a !is condition, narrow to complement
                it = knownType.minus(type);
            }
            else {
                //narrow to the intersection of the outer type 
                //and the type specified in the condition
                it = intersectionType(type, knownType, that.getUnit());
            }
            if (it.getDeclaration() instanceof NothingType) {
                if (that.getNot()) {
                    /*that.addError("tests assignability to Nothing type: " +
                            knownType.getProducedTypeName(unit) + " is a subtype of " + 
                            type.getProducedTypeName(unit));*/
                }
                else {
                    that.addError("tests assignability to Nothing type: intersection of " +
                            knownType.getProducedTypeName(unit) + " and " + 
                            type.getProducedTypeName(unit) + " is empty");
                }
            }
            v.getType().setTypeModel(it);
            v.getDeclarationModel().setType(it);
        }
        else if (that.getExpression()!=null) {
            that.getExpression().visit(this);
        }
        /*if (that.getExpression()!=null) {
            that.getExpression().visit(this);
        }*/
        checkReified(that, type);
    }

    private void checkReified(Node that, ProducedType type) {
        if (type!=null) {
            TypeDeclaration dec = type.getDeclaration();
            if (dec instanceof UnionType) {
                for (ProducedType pt: dec.getCaseTypes()) {
                    checkReified(that, pt);
                }
            }
            else if (dec instanceof IntersectionType) {
                for (ProducedType pt: dec.getSatisfiedTypes()) {
                    checkReified(that, pt);
                }
            }
            else if (dec instanceof TypeParameter) {
//                that.addWarning("type parameter in assignability condition not yet supported (until we implement reified generics)");
            }
            else if (isGeneric(dec)) {
                List<TypeParameter> params = dec.getTypeParameters();
                List<ProducedType> args = type.getTypeArgumentList();
                if (params.size()==args.size()) {
                    for (int i=0; i<params.size(); i++) {
                        TypeParameter tp = params.get(i);
                        ProducedType ta = args.get(i);
                        if (ta!=null) {
                            if (tp.isCovariant()) {
                                List<ProducedType> list = new ArrayList<ProducedType>();
                                addToIntersection(list, unit.getAnythingDeclaration().getType(), unit);
                                for (ProducedType st: tp.getSatisfiedTypes()) {
                                    if (!tp.isSelfType()) {
                                        st = st.substitute(type.getTypeArguments());
                                        addToIntersection(list, st, unit);
                                    }
                                }
                                IntersectionType ut = new IntersectionType(unit);
                                ut.setSatisfiedTypes(list);
//                                if (!ut.getType().isSubtypeOf(ta)) {
//                                    that.addWarning("type argument to covariant (out) type parameter in assignability condition must be " +
//                                            ut.getType().getProducedTypeName(unit) + " (until we implement reified generics)");
//                                }
                            }
                            else if (tp.isContravariant()) {
//                                if (!(ta.getDeclaration() instanceof NothingType)) {
//                                    that.addWarning("type argument to contravariant (in) type parameter in assignability condition must be Nothing (until we implement reified generics)");
//                                }
                            }
                            else {
//                                that.addWarning("type argument to invariant type parameter in assignability condition not yet supported (until we implement reified generics)");
                            }
                        }
                    }
                }
                //else there is another error, so don't bother
                //with this check at all
            }
        }
    }

    @Override public void visit(Tree.SatisfiesCondition that) {
        super.visit(that);
        that.addWarning("satisfies conditions not yet supported");
    }
    
    @Override public void visit(Tree.ExistsOrNonemptyCondition that) {
        //don't recurse to the Variable, since we don't
        //want to check that the specifier expression is
        //assignable to the declared variable type
        //(nor is it possible to infer the variable type)
        ProducedType t = null;
        Node n = that;
        Tree.Term term = null;
        Tree.Variable v = that.getVariable();
        if (v!=null) {
            //v.getType().visit(this);
            defaultTypeToAnything(v);
            Tree.SpecifierExpression se = v.getSpecifierExpression();
            if (se!=null && se.getExpression()!=null) {
                se.visit(this);
                if (that instanceof Tree.ExistsCondition) {
                    inferDefiniteType(v, se);
                    checkOptionalType(v, se);
                }
                else if (that instanceof Tree.NonemptyCondition) {
                    inferNonemptyType(v, se);
                    checkEmptyOptionalType(v, se);
                }
                t = se.getExpression().getTypeModel();
                n = v;
                checkReferenceIsNonVariable(v, se);
                initOriginalDeclaration(v);
                term = se.getExpression().getTerm();
            }
        }
        else if (that.getExpression()!=null) {
            //note: this is only here to handle
            //      erroneous syntax elegantly
            that.getExpression().visit(this);
            t = that.getExpression().getTypeModel();
            term = that.getExpression().getTerm();
        }
        /*Tree.Expression e = that.getExpression();
        if (e!=null) {
            e.visit(this);
            t = e.getTypeModel();
            n = e;
        }*/
        if (that instanceof Tree.ExistsCondition) {
            checkOptional(t, term, n);
        }
        else if (that instanceof Tree.NonemptyCondition) {
            checkEmpty(t, term, n);
        }
    }

    private void defaultTypeToAnything(Tree.Variable v) {
        /*if (v.getType().getTypeModel()==null) {
            v.getType().setTypeModel( getAnythingDeclaration().getType() );
        }*/
        v.getType().visit(this);
        if (v.getDeclarationModel().getType()==null) {
            v.getDeclarationModel().setType( defaultType() );
        }
    }

    private void checkReferenceIsNonVariable(Tree.Variable v,
            Tree.SpecifierExpression se) {
        if (v.getType() instanceof Tree.SyntheticVariable) {
            checkReferenceIsNonVariable((Tree.BaseMemberExpression) se.getExpression().getTerm());
        }
    }

    private void checkReferenceIsNonVariable(Tree.BaseMemberExpression ref) {
        Declaration d = ref.getDeclaration();
        if (d!=null) {
            String help=" (assign to a new local value to narrow type)";
            if (!(d instanceof Value || d instanceof Getter || d instanceof ValueParameter)) {
                ref.addError("referenced declaration is not a value: " + 
                        d.getName(unit), 3100);
            }
            else if (isNonConstant(d)) {
                ref.addError("referenced value is non-constant: " + 
                        d.getName(unit) + help, 3100);
            }
            else if (d.isDefault() || d.isFormal()) {
                ref.addError("referenced value may be refined by a non-constant value: " + 
                        d.getName(unit) + help, 3100);
            }
        }
    }

    private boolean isNonConstant(Declaration d) {
        return d instanceof Getter || d instanceof Value && 
                (((Value) d).isVariable() || ((Value) d).isTransient());
    }
    
    private void checkEmpty(ProducedType t, Tree.Term term, Node n) {
        /*if (t==null) {
            n.addError("expression must be a type with fixed size: type not known");
        }
        else*/ if (t!=null && !t.isUnknown() && !unit.isEmptyType(t)) {
            term.addError("expression must be a possibly-empty type: " + 
                    t.getProducedTypeName(unit) + " is not possibly-empty");
        }
    }
    
    private void checkOptional(ProducedType t, Tree.Term term, Node n) {
        /*if (t==null) {
            n.addError("expression must be of optional type: type not known");
        }
        else*/ 
        if (t!=null && !t.isUnknown() && !unit.isOptionalType(t) && 
                !hasUncheckedNulls(term)) {
            term.addError("expression must be of optional type: " +
                    t.getProducedTypeName(unit) + " is not optional");
        }
    }

    @Override public void visit(Tree.BooleanCondition that) {
        super.visit(that);
        if (that.getExpression()!=null) {
            ProducedType t = that.getExpression().getTypeModel();
            if (!t.isUnknown()) {
                checkAssignable(t, unit.getBooleanDeclaration().getType(), that, 
                        "expression must be of boolean type");
            }
        }
    }

    @Override public void visit(Tree.Resource that) {
        that.addWarning("try with resource not yet supported");
        super.visit(that);
        ProducedType t = null;
        Node typedNode = null;
        if (that.getExpression()!=null) {
            Tree.Expression e = that.getExpression();
            t = e.getTypeModel();
            typedNode = e;
            if (e.getTerm() instanceof Tree.InvocationExpression) {
                Tree.InvocationExpression ie = (Tree.InvocationExpression) e.getTerm();
                if (!(ie.getPrimary() instanceof Tree.BaseTypeExpression 
                        || ie.getPrimary() instanceof Tree.QualifiedTypeExpression)) {
                    e.addError("resource expression is not an unqualified value reference or instantiation");
                }
            }
            else if (!(e.getTerm() instanceof Tree.BaseMemberExpression)){
                e.addError("resource expression is not an unqualified value reference or instantiation");
            }
        }
        else if (that.getVariable()!=null) {
            t = that.getVariable().getType().getTypeModel();
            typedNode = that.getVariable().getType();
            Tree.SpecifierExpression se = that.getVariable().getSpecifierExpression();
            if (se==null) {
                that.getVariable().addError("missing resource specifier");
            }
            else if (typedNode instanceof Tree.ValueModifier){
                typedNode = se.getExpression();
            }
        }
        if (typedNode!=null) {
            if (!t.isUnknown()) {
                checkAssignable(t, unit.getCloseableDeclaration().getType(), typedNode, 
                        "resource must be closeable");
            }
        }
    }
    
    @Override public void visit(Tree.ForIterator that) {
        super.visit(that);
        Tree.SpecifierExpression se = that.getSpecifierExpression();
        if (se!=null) {
            Tree.Expression e = se.getExpression();
            if (e!=null) {
                if (e.getTypeModel()!=null && e.getTypeModel()
                        .isSubtypeOf(unit.getEmptyDeclaration().getType())) {
                    se.addError("iterated expression is definitely empty");
                }
            }
        }
    }

    @Override public void visit(Tree.ValueIterator that) {
        super.visit(that);
        Tree.Variable v = that.getVariable();
        if (v!=null) {
            inferContainedType(v, that.getSpecifierExpression());
            checkContainedType(v, that.getSpecifierExpression());
        }
    }

    @Override public void visit(Tree.KeyValueIterator that) {
        super.visit(that);
        Tree.Variable kv = that.getKeyVariable();
        Tree.Variable vv = that.getValueVariable();
        if (kv!=null && vv!=null) {
            inferKeyType(kv, that.getSpecifierExpression());
            inferValueType(vv, that.getSpecifierExpression());
            checkKeyValueType(kv, vv, that.getSpecifierExpression());
        }
    }
    
    @Override public void visit(Tree.AttributeDeclaration that) {
        super.visit(that);
        Tree.SpecifierOrInitializerExpression sie = that.getSpecifierOrInitializerExpression();
        Tree.Type type = that.getType();
        inferType(that, sie);
        if (type!=null) {
            ProducedType t = type.getTypeModel();
            if (!t.isUnknown()) {
                checkType(t, that.getDeclarationModel().getName(), sie, 2100);
            }
        }
    }
    
    @Override public void visit(Tree.ParameterizedExpression that) {
        super.visit(that);
        Tree.Term p = that.getPrimary();
        if (p.getTypeModel()!=null) {
            ProducedType pt = p.getTypeModel();
            if (pt!=null) {
                for (int j=0; j<that.getParameterLists().size(); j++) {
                    Tree.ParameterList pl = that.getParameterLists().get(j);
                    ProducedType ct = pt.getSupertype(unit.getCallableDeclaration());
                    if (ct==null) {
                        pl.addError("no matching parameter list in referenced declaration");
                    }
                    else if (ct.getTypeArgumentList().size()>=2) {
                        ProducedType tupleType = ct.getTypeArgumentList().get(1);
                        List<ProducedType> argTypes = getTupleElementTypes(tupleType);
                        boolean variadic = isTupleLengthUnbounded(tupleType);
                        List<Tree.Parameter> params = pl.getParameters();
                        if (argTypes.size()!=params.size()) {
                            pl.addError("wrong number of declared parameters: must have " + argTypes.size() + " parameters");
                        }
                        for (int i=0; i<argTypes.size()&&i<params.size(); i++) {
                            ProducedType at = argTypes.get(i);
                            Tree.Parameter param = params.get(i);
                            ProducedType t = param.getDeclarationModel()
                                    .getProducedTypedReference(null, Collections.<ProducedType>emptyList()).getFullType();
                            checkAssignable(t, at, param.getType(), 
                                    "declared parameter type must be a subtype of type declared in function declaration");
                        }
                        if (!params.isEmpty()) {
                            Tree.Parameter lastParam = params.get(params.size()-1);
                            boolean refSequenced = lastParam.getDeclarationModel().isSequenced();
                            if (refSequenced && !variadic) {
                                lastParam.addError("parameter list in referenced declaration does not have a sequenced parameter");
                            }
                            if (!refSequenced && variadic) {
                                lastParam.addError("parameter list in referenced declaration has a sequenced parameter");                            
                            }
                        }
                        pt = ct.getTypeArgumentList().get(0);
                        that.setTypeModel(pt);
                    }
                }
            }
        }
    }
    
    @Override public void visit(Tree.SpecifierStatement that) {
        super.visit(that);
        boolean hasParams = false;
        Tree.Term me = that.getBaseMemberExpression();
        while (me instanceof Tree.ParameterizedExpression) {
            hasParams = true;
            me = ((Tree.ParameterizedExpression) me).getPrimary();
        }
        Tree.SpecifierExpression sie = that.getSpecifierExpression();
        if (me instanceof Tree.BaseMemberExpression) {
            Tree.BaseMemberExpression bme = (Tree.BaseMemberExpression) me;
            Declaration d = bme.getDeclaration();
            if (d instanceof TypedDeclaration) {
                that.setDeclaration((TypedDeclaration) d);
                Scope cs = that.getScope().getContainer();
                if (cs instanceof ClassOrInterface && 
                        !d.isDefinedInScope(cs)) {
                    //then it must be inherited ... TODO: is this totally correct? 
                    //so it's actually a refinement of a formal declaration!
                    if (d instanceof Value) {
                        refine((Value) d, bme, that);
                    }
                    else if (d instanceof Method) {
                        refine((Method) d, bme, that);
                    }
                    else {
                        //TODO!
                        bme.addError("not a reference to a formal attribute: " + 
                                d.getName(unit));
                    }
                }
                else if (d instanceof MethodOrValue) {
                    MethodOrValue mv = (MethodOrValue) d;
                    if (mv.isShortcutRefinement()) {
                        bme.addError("already specified: " + d.getName(unit));
                    }
                    else if (d.isToplevel() && !mv.isVariable() && !mv.isLate()) {
                        that.addError("cannot specify non-variable toplevel value here: " + 
                                d.getName(unit), 803);
                    }
                }
                if (hasParams && d instanceof Method && 
                        ((Method) d).isDeclaredVoid() && 
                        !isSatementExpression(sie.getExpression())) {
                    that.addError("method is declared void so specified expression must be a statement: " + 
                            d.getName(unit));
                }
                if (d instanceof Value && 
                        that.getSpecifierExpression() instanceof Tree.LazySpecifierExpression) {
                    ((Value) d).setTransient(true);
                }
                
                ProducedType t = that.getBaseMemberExpression().getTypeModel();
                if (that.getBaseMemberExpression()==me && d instanceof Method) {
                    //if the declaration of the method has
                    //defaulted parameters, we should ignore
                    //that when determining if the RHS is
                    //an acceptable implementation of the
                    //method
                    //TODO: this is a pretty nasty way to
                    //      handle the problem
                    t = eraseDefaultedParameters(t);
                }
                if (!t.isUnknown()) {
                    checkType(t, d.getName(unit), sie, 2100);
                }
            }
            if (that.getBaseMemberExpression() instanceof Tree.ParameterizedExpression) {
                if (!(sie instanceof Tree.LazySpecifierExpression)) {
                    that.addError("functions with parameters must be specified using =>");
                }
            }
            else {
                if (sie instanceof Tree.LazySpecifierExpression && d instanceof Method) {
                    that.addError("functions without parameters must be specified using =");
                }
            }
        }
        else {
            me.addError("illegal specification statement");
        }
    }
    
    boolean isSatementExpression(Tree.Expression e) {
        if (e==null) {
            return false;
        }
        else {
            Tree.Term t = e.getTerm();
            return t instanceof Tree.InvocationExpression ||
                    t instanceof Tree.PostfixOperatorExpression ||
                    t instanceof Tree.AssignmentOp ||
                    t instanceof Tree.PrefixOperatorExpression;
        }
    }
    
    /*boolean isVoidMethodReference(Tree.Expression e) {
        //TODO: correctly handle multiple parameter lists!
        Tree.Term term = e.getTerm();
        ProducedType tm = term.getTypeModel();
        if (tm!=null && tm.isExactly(unit.getAnythingDeclaration().getType())) {
            if (term instanceof Tree.InvocationExpression) {
                Tree.InvocationExpression ie = (Tree.InvocationExpression) term;
                if (ie.getPrimary() instanceof Tree.MemberOrTypeExpression) {
                    Tree.MemberOrTypeExpression mte = (Tree.MemberOrTypeExpression) ie.getPrimary();
                    if (mte.getDeclaration() instanceof Functional) {
                        return ((Functional) mte.getDeclaration()).isDeclaredVoid();
                    }
                }
            }
        }
        return false;
    }*/

    private ProducedType eraseDefaultedParameters(ProducedType t) {
        ProducedType ct = t.getSupertype(unit.getCallableDeclaration());
        if (ct!=null) {
            List<ProducedType> typeArgs = ct.getTypeArgumentList();
            if (typeArgs.size()>=2) {
                ProducedType rt = typeArgs.get(0);
                ProducedType pts = typeArgs.get(1);
                List<ProducedType> argTypes = getTupleElementTypes(pts);
                boolean variadic = isTupleLengthUnbounded(pts);
                boolean atLeastOne = isTupleVariantAtLeastOne(pts);
                if (variadic) {
                    ProducedType spt = argTypes.get(argTypes.size()-1);
                    argTypes.set(argTypes.size()-1, unit.getIteratedType(spt));
                }
                return producedType(unit.getCallableDeclaration(), rt, 
                        unit.getTupleType(argTypes, variadic, atLeastOne, -1));
            }
        }
        return t;
    }

    private void refine(Value sv, Tree.BaseMemberExpression bme,
            Tree.SpecifierStatement that) {
        ClassOrInterface c = (ClassOrInterface) that.getScope().getContainer();
        if (sv.isVariable()) {
            that.addError("attribute is variable: " + 
                    RefinementVisitor.message(sv));
        }
        if (!sv.isFormal() && !sv.isDefault()
                && !sv.isShortcutRefinement()) { //this condition is here to squash a dupe message
            bme.addError("attribute is not formal: " + 
                    RefinementVisitor.message(sv));
        }
        Value v = new Value();
        v.setName(sv.getName());
        /*if (sie!=null) {
            v.setType(sie.getExpression().getTypeModel());
        }*/
        v.setType(getRefinedMember(sv, c).getType());
        v.setShared(true);
        v.setActual(true);
        v.setRefinedDeclaration(sv.getRefinedDeclaration());
        v.setUnit(unit);
        v.setContainer(c);
        v.setScope(c);
        v.setShortcutRefinement(true);
        DeclarationVisitor.setVisibleScope(v);
        c.getMembers().add(v);
        bme.setDeclaration(v);
        //bme.setTypeModel(v.getType());
        that.setRefinement(true);
        that.setDeclaration(v);
    }

    private ProducedReference getRefinedMember(MethodOrValue sv, ClassOrInterface c) {
        return sv.getProducedReference(c.getType().getSupertype((TypeDeclaration)sv.getContainer()), 
                Collections.<ProducedType>emptyList());
    }

    private void refine(Method sm, Tree.BaseMemberExpression bme,
            Tree.SpecifierStatement that) {
        ClassOrInterface c = (ClassOrInterface) that.getScope().getContainer();
        if (!sm.isFormal() && !sm.isDefault()
                && !sm.isShortcutRefinement()) { //this condition is here to squash a dupe message
            bme.addError("method is not formal: " + 
                    RefinementVisitor.message(sm));
        }
        Method m = new Method();
        m.setName(sm.getName());
        /*if (sie!=null) {
            v.setType(sie.getExpression().getTypeModel());
        }*/
        ProducedReference rm = getRefinedMember(sm, c);
        m.setType(rm.getType());
        List<Tree.ParameterList> tpls;
        Tree.Term me = that.getBaseMemberExpression();
        if (me instanceof Tree.ParameterizedExpression) {
            tpls = ((Tree.ParameterizedExpression) me).getParameterLists();
        }
        else {
            tpls = Collections.emptyList();
        }
        int i=0;
        for (ParameterList pl: sm.getParameterLists()) {
            ParameterList l = new ParameterList();
            Tree.ParameterList tpl = tpls.size()<=i ? 
                    null : tpls.get(i++);
            int j=0;
            for (Parameter p: pl.getParameters()) {
                //TODO: meaningful errors when parameters don't line up
                //      currently this is handled elsewhere, but we can
                //      probably do it better right here
                if (tpl==null || tpl.getParameters().size()<=j) {
                    Parameter vp = new ValueParameter();
                    vp.setSequenced(p.isSequenced());
                    vp.setDefaulted(p.isDefaulted());
                    vp.setName(p.getName());
                    vp.setType(rm.getTypedParameter(p).getFullType());
                    vp.setDeclaration(m);
                    vp.setContainer(m);
                    vp.setScope(m);
                    l.getParameters().add(vp);
                }
                else {
                    Tree.Parameter tp = tpl.getParameters().get(j++);
                    l.getParameters().add(tp.getDeclarationModel());
                }
            }
            m.getParameterLists().add(l);
        }
        if (!sm.getTypeParameters().isEmpty()) {
            bme.addError("method has type parameters: " +  
                    RefinementVisitor.message(sm));
        }
        m.setShared(true);
        m.setActual(true);
        m.setRefinedDeclaration(sm.getRefinedDeclaration());
        m.setUnit(unit);
        m.setContainer(c);
        m.setShortcutRefinement(true);
        m.setDeclaredAnything(sm.isDeclaredVoid());
        DeclarationVisitor.setVisibleScope(m);
        c.getMembers().add(m);
        bme.setDeclaration(m);
        //bme.setTypeModel(v.getType());
        that.setRefinement(true);
        that.setDeclaration(m);
    }

    @Override public void visit(Tree.Parameter that) {
        super.visit(that);
        Tree.Type type = that.getType();
        Tree.DefaultArgument da = that.getDefaultArgument();
        Parameter p = that.getDeclarationModel();
        if (da!=null && type!=null) {
            Tree.SpecifierExpression se = da.getSpecifierExpression();
            checkType(type.getTypeModel(), p.getName(), se, 2100);
        }
        if (type instanceof Tree.LocalModifier && 
                !(that instanceof Tree.InitializerParameter)) {
            type.setTypeModel(new UnknownType(unit).getType());
            if (!dynamic) {
                type.addError("parameter may not have inferred type: " + 
                        p.getName());
            }
            else if (p.getDeclaration().isShared()) {
                type.addError("shared parameter may not have inferred type: " + 
                        p.getName());
            }
        }
    }

    @Override
    public void visit(Tree.ValueParameterDeclaration that) {
        if (that.getType() instanceof Tree.LocalModifier) {
            ValueParameter d = that.getDeclarationModel();
            if (d!=null) {
                that.getType().setTypeModel(d.getType());
            }
        }
        super.visit(that);
    }
    
    private void checkType(ProducedType declaredType, 
            Tree.SpecifierOrInitializerExpression sie) {
        if (sie!=null && sie.getExpression()!=null) {
            ProducedType t = sie.getExpression().getTypeModel();
            if (!t.isUnknown()) {
                checkAssignable(t, declaredType, sie, 
                        "specified expression must be assignable to declared type");
            }
        }
    }

    private void checkType(ProducedType declaredType, String name,
            Tree.SpecifierOrInitializerExpression sie, int code) {
        if (sie!=null && sie.getExpression()!=null) {
            ProducedType t = sie.getExpression().getTypeModel();
            if (!t.isUnknown()) {
                checkAssignable(t, declaredType, sie, 
                        "specified expression must be assignable to declared type of " + name,
                        code);
            }
        }
    }

    private void checkFunctionType(ProducedType et, Tree.Type that, 
            Tree.SpecifierExpression se) {
        if (et!=null && !et.isUnknown()) {
            checkAssignable(et, that.getTypeModel(), se, 
                    "specified expression type must be assignable to declared return type");
        }
    }

    private void checkOptionalType(Tree.Variable var, 
            Tree.SpecifierExpression se) {
        if (var.getType()!=null) {
            ProducedType vt = var.getType().getTypeModel();
            Tree.Expression e = se.getExpression();
            if (se!=null && e!=null) {
                ProducedType set = e.getTypeModel();
                if (set!=null) {
                    if (!vt.isUnknown() && !set.isUnknown()) {
                        checkAssignable(unit.getDefiniteType(set), vt, se, 
                                "specified expression must be assignable to declared type");
                    }
                }
            }
        }
    }

    private void checkEmptyOptionalType(Tree.Variable var, 
            Tree.SpecifierExpression se) {
        if (var.getType()!=null) {
            ProducedType vt = var.getType().getTypeModel();
            Tree.Expression e = se.getExpression();
            if (se!=null && e!=null) {
                ProducedType set = e.getTypeModel();
                if (!vt.isUnknown() && !set.isUnknown()) {
                    checkType(unit.getOptionalType(unit.getPossiblyNoneType(vt)), se);
                }
            }
        }
    }

    private void checkContainedType(Tree.Variable var, 
            Tree.SpecifierExpression se) {
        if (var.getType()!=null) {
            ProducedType vt = var.getType().getTypeModel();
            if (!vt.isUnknown()) {
                checkType(unit.getIterableType(vt), se);
            }
        }
    }

    private void checkKeyValueType(Tree.Variable key, Tree.Variable value, 
            Tree.SpecifierExpression se) {
        if (key.getType()!=null && value.getType()!=null) {
            ProducedType kt = key.getType().getTypeModel();
            ProducedType vt = value.getType().getTypeModel();
            if (!kt.isUnknown() && !vt.isUnknown()) {
                checkType(unit.getIterableType(unit.getEntryType(kt, vt)), se);
            }
        }
    }

    @Override public void visit(Tree.AttributeGetterDefinition that) {
        Tree.Type rt = beginReturnScope(that.getType());
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnScope(rt, that.getDeclarationModel());
        endReturnDeclaration(od);
        Setter setter = that.getDeclarationModel().getSetter();
        if (setter!=null) {
            setter.getParameter().setType(that.getDeclarationModel().getType());
        }
    }

    @Override public void visit(Tree.AttributeArgument that) {
        Tree.SpecifierExpression se = that.getSpecifierExpression();
        Tree.Type type = that.getType();
        if (se==null) {
            Tree.Type rt = beginReturnScope(type);
            Declaration od = beginReturnDeclaration(that.getDeclarationModel());
            super.visit(that);
            endReturnDeclaration(od);
            endReturnScope(rt, that.getDeclarationModel());
        }
        else {
            super.visit(that);
            inferType(that, se);
            if (type!=null) {
                ProducedType t = type.getTypeModel();
                if (!t.isUnknown()) {
                    checkType(t, that.getDeclarationModel().getName(), se, 2100);
                }
            }
        }
    }

    @Override public void visit(Tree.AttributeSetterDefinition that) {
        Tree.Type rt = beginReturnScope(that.getType());
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, that.getDeclarationModel());
    }

    @Override public void visit(Tree.MethodDeclaration that) {
        super.visit(that);
        Tree.SpecifierExpression se = that.getSpecifierExpression();
        if (se!=null) {
            Tree.Expression e = se.getExpression();
            if (e!=null) {
                ProducedType returnType = e.getTypeModel();
                inferFunctionType(that, returnType);
                if (that.getType()!=null) {
                    checkFunctionType(returnType, that.getType(), se);
                }
                if (that.getType() instanceof Tree.VoidModifier && 
                        !isSatementExpression(e)) {
                    se.addError("method is declared void so specified expression must be a statement: " +
                            that.getDeclarationModel().getName());
                }
            }
        }
    }

    @Override public void visit(Tree.MethodDefinition that) {
        Tree.Type rt = beginReturnScope(that.getType());           
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, that.getDeclarationModel());
    }

    @Override public void visit(Tree.MethodArgument that) {
        Tree.SpecifierExpression se = that.getSpecifierExpression();
        Method d = that.getDeclarationModel();
        if (se==null) {
            Tree.Type rt = beginReturnScope(that.getType());           
            Declaration od = beginReturnDeclaration(d);
            super.visit(that);
            endReturnDeclaration(od);
            endReturnScope(rt, d);
        }
        else {
            super.visit(that);
            Tree.Expression e = se.getExpression();
            if (e!=null) {
                ProducedType returnType = e.getTypeModel();
                inferFunctionType(that, returnType);
                if (that.getType()!=null) {
                    checkFunctionType(returnType, that.getType(), se);
                }
                if (d.isDeclaredVoid() && !isSatementExpression(e)) {
                    se.addError("functional argument is declared void so specified expression must be a statement: " + 
                            d.getName());
                }
            }
        }
    }

    @Override public void visit(Tree.ClassDefinition that) {
        Tree.Type rt = beginReturnScope(new Tree.VoidModifier(that.getToken()));
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, null);
        Class c = that.getDeclarationModel();
        if (!c.isAbstract()) {
            validateEnumeratedSupertypes(that, c);
        }
    }
    
    @Override public void visit(Tree.ClassOrInterface that) {
        super.visit(that);
        validateEnumeratedSupertypeArguments(that, that.getDeclarationModel());
    }

    @Override public void visit(Tree.InterfaceDefinition that) {
        Tree.Type rt = beginReturnScope(null);
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, null);
    }

    @Override public void visit(Tree.ObjectDefinition that) {
        Tree.Type rt = beginReturnScope(new Tree.VoidModifier(that.getToken()));
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, null);
        validateEnumeratedSupertypes(that, that.getAnonymousClass());
    }

    @Override public void visit(Tree.ObjectArgument that) {
        Tree.Type rt = beginReturnScope(new Tree.VoidModifier(that.getToken()));
        Declaration od = beginReturnDeclaration(that.getDeclarationModel());
        super.visit(that);
        endReturnDeclaration(od);
        endReturnScope(rt, null);
        validateEnumeratedSupertypes(that, that.getAnonymousClass());
    }
    
    //TODO: this whole method can be removed once the backend
    //      implements full support for the new class alias stuff
    @Override public void visit(Tree.ClassDeclaration that) {
        super.visit(that);
        Class alias = that.getDeclarationModel();
        Class c = alias.getExtendedTypeDeclaration();
        if (c!=null) {
            if (c.isAbstract()) {
                if (!alias.isFormal()&&!alias.isAbstract()) {
                    that.addError("alias of abstract class must be annotated abstract"); //TODO: error code
                }
            }
            ProducedType at = alias.getExtendedType();
            ParameterList pl = c.getParameterList();
            ParameterList apl = alias.getParameterList();
            if (pl!=null&&apl!=null) {
                int cps = pl.getParameters().size();
                int aps = apl.getParameters().size();
                if (cps!=aps) {
                    that.addWarning("wrong number of initializer parameters declared by class alias: " + 
                            alias.getName());
                }
                for (int i=0; i<(cps<=aps ? cps : aps); i++) {
                    Parameter ap = apl.getParameters().get(i);
                    Parameter cp = pl.getParameters().get(i);
                    ProducedType pt = at.getTypedParameter(cp).getType();
                    ap.setAliasedParameter(cp);
                    //TODO: properly check type of functional parameters!!
                    checkAssignableWithWarning(ap.getType(), pt, that, "alias parameter " + 
                            ap.getName() + " must be assignable to corresponding class parameter " +
                            cp.getName());
                }
            }
        }
    }
    
    private void inferType(Tree.TypedDeclaration that, 
            Tree.SpecifierOrInitializerExpression spec) {
        if (that.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) that.getType();
            if (spec!=null) {
                setType(local, spec, that);
            }
            else {
//                local.addError("could not infer type of: " + 
//                        name(that.getIdentifier()));
            }
        }
    }

    private void inferType(Tree.AttributeArgument that, 
            Tree.SpecifierOrInitializerExpression spec) {
        if (that.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) that.getType();
            if (spec!=null) {
                setType(local, spec, that);
            }
            else {
//                local.addError("could not infer type of: " + 
//                        name(that.getIdentifier()));
            }
        }
    }

    private void inferFunctionType(Tree.TypedDeclaration that, ProducedType et) {
        if (that.getType() instanceof Tree.FunctionModifier) {
            Tree.FunctionModifier local = (Tree.FunctionModifier) that.getType();
            if (et!=null) {
                setFunctionType(local, et, that);
            }
        }
    }
    
    private void inferFunctionType(Tree.MethodArgument that, ProducedType et) {
        if (that.getType() instanceof Tree.FunctionModifier) {
            Tree.FunctionModifier local = (Tree.FunctionModifier) that.getType();
            if (et!=null) {
                setFunctionType(local, et, that);
            }
        }
    }
    
    private void inferDefiniteType(Tree.Variable that, 
            Tree.SpecifierExpression se) {
        if (that.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) that.getType();
            if (se!=null) {
                setTypeFromOptionalType(local, se, that);
            }
            else {
//                local.addError("could not infer type of: " + 
//                        name(that.getIdentifier()));
            }
        }
    }

    private void inferNonemptyType(Tree.Variable that, 
            Tree.SpecifierExpression se) {
        if (that.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) that.getType();
            if (se!=null) {
                setTypeFromEmptyType(local, se, that);
            }
            else {
//                local.addError("could not infer type of: " + 
//                        name(that.getIdentifier()));
            }
        }
    }

    private void inferContainedType(Tree.Variable that, 
            Tree.SpecifierExpression se) {
        if (that.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) that.getType();
            if (se!=null) {
                setTypeFromIterableType(local, se, that);
            }
            else {
//                local.addError("could not infer type of: " + 
//                        name(that.getIdentifier()));
            }
        }
    }

    private void inferKeyType(Tree.Variable key, 
            Tree.SpecifierExpression se) {
        if (key.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) key.getType();
            if (se!=null) {
                setTypeFromKeyType(local, se, key);
            }
            else {
//                local.addError("could not infer type of key: " + 
//                        name(key.getIdentifier()));
            }
        }
    }

    private void inferValueType(Tree.Variable value, 
            Tree.SpecifierExpression se) {
        if (value.getType() instanceof Tree.LocalModifier) {
            Tree.LocalModifier local = (Tree.LocalModifier) value.getType();
            if (se!=null) {
                setTypeFromValueType(local, se, value);
            }
            else {
//                local.addError("could not infer type of value: " + 
//                        name(value.getIdentifier()));
            }
        }
    }
    
    private void setTypeFromOptionalType(Tree.LocalModifier local, 
            Tree.SpecifierExpression se, Tree.Variable that) {
        Tree.Expression e = se.getExpression();
        if (e!=null) {
            ProducedType expressionType = e.getTypeModel();
            if (expressionType!=null && 
                    !expressionType.isUnknown()) {
                ProducedType t;
                if (unit.isOptionalType(expressionType)) {
                    t = unit.getDefiniteType(expressionType);
                }
                else {
                    t=expressionType;
                }
                local.setTypeModel(t);
                that.getDeclarationModel().setType(t);
            }
            //        local.addError("could not infer type of: " + 
            //                name(that.getIdentifier()));
        }
    }
    
    private void setTypeFromEmptyType(Tree.LocalModifier local, 
            Tree.SpecifierExpression se, Tree.Variable that) {
        Tree.Expression e = se.getExpression();
        if (e!=null) {
            ProducedType expressionType = e.getTypeModel();
            if (expressionType!=null && 
                    !expressionType.isUnknown()) {
//                if (expressionType.getDeclaration() instanceof Interface && 
//                        expressionType.getDeclaration().equals(unit.getSequentialDeclaration())) {
//                    expressionType = unit.getEmptyType(unit.getSequenceType(expressionType.getTypeArgumentList().get(0)));
//                }
                ProducedType t;
                if (unit.isEmptyType(expressionType)) {
                    t = unit.getNonemptyDefiniteType(expressionType);
                }
                else {
                    t = expressionType;
                }
                local.setTypeModel(t);
                that.getDeclarationModel().setType(t);
            }
            //        local.addError("could not infer type of: " + 
            //                name(that.getIdentifier()));
        }
    }
    
    private void setTypeFromIterableType(Tree.LocalModifier local, 
            Tree.SpecifierExpression se, Tree.Variable that) {
        if (se.getExpression()!=null) {
            ProducedType expressionType = se.getExpression().getTypeModel();
            if (expressionType!=null) {
                if (unit.isIterableType(expressionType)) {
                    ProducedType t = unit.getIteratedType(expressionType);
                    local.setTypeModel(t);
                    that.getDeclarationModel().setType(t);
                    return;
                }
            }
        }
//        local.addError("could not infer type of: " + 
//                name(that.getIdentifier()));
    }
    
    private void setTypeFromKeyType(Tree.LocalModifier local,
            Tree.SpecifierExpression se, Tree.Variable that) {
        Tree.Expression e = se.getExpression();
        if (e!=null) {
            ProducedType expressionType = e.getTypeModel();
            if (expressionType!=null) {
                if (unit.isIterableType(expressionType)) {
                    ProducedType entryType = unit.getIteratedType(expressionType);
                    if (entryType!=null) {
                        if (unit.isEntryType(entryType)) {
                            ProducedType et = unit.getKeyType(entryType);
                            local.setTypeModel(et);
                            that.getDeclarationModel().setType(et);
                            return;
                        }
                    }
                }
            }
        }
//        local.addError("could not infer type of: " + 
//                name(that.getIdentifier()));
    }
    
    private void setTypeFromValueType(Tree.LocalModifier local,
            Tree.SpecifierExpression se, Tree.Variable that) {
        Tree.Expression e = se.getExpression();
        if (e!=null) {
            ProducedType expressionType = e.getTypeModel();
            if (expressionType!=null) {
                if (unit.isIterableType(expressionType)) {
                    ProducedType entryType = unit.getIteratedType(expressionType);
                    if (entryType!=null) {
                        if (unit.isEntryType(entryType)) {
                            ProducedType et = unit.getValueType(entryType);
                            local.setTypeModel(et);
                            that.getDeclarationModel().setType(et);
                            return;
                        }
                    }
                }
            }
        }
//        local.addError("could not infer type of: " + 
//                name(that.getIdentifier()));
    }
    
    private void setType(Tree.LocalModifier local, 
            Tree.SpecifierOrInitializerExpression s, 
            Tree.TypedDeclaration that) {
        Tree.Expression e = s.getExpression();
        if (e!=null) {
            ProducedType type = e.getTypeModel();
            if (type!=null) {
                ProducedType t = unit.denotableType(type);
                local.setTypeModel(t);
                that.getDeclarationModel().setType(t);
            }
        }
    }
        
    private void setType(Tree.LocalModifier local, 
            Tree.SpecifierOrInitializerExpression s, 
            Tree.AttributeArgument that) {
        Tree.Expression e = s.getExpression();
        if (e!=null) {
            ProducedType type = e.getTypeModel();
            if (type!=null) {
                ProducedType t = unit.denotableType(type);
                local.setTypeModel(t);
                that.getDeclarationModel().setType(t);
            }
        }
    }
        
    private void setFunctionType(Tree.FunctionModifier local, 
            ProducedType et, Tree.TypedDeclaration that) {
        ProducedType t = unit.denotableType(et);
        local.setTypeModel(t);
        that.getDeclarationModel().setType(t);
    }
        
    private void setFunctionType(Tree.FunctionModifier local, 
            ProducedType et, Tree.MethodArgument that) {
        ProducedType t = unit.denotableType(et);
        local.setTypeModel(t);
        that.getDeclarationModel().setType(t);
    }
        
    @Override public void visit(Tree.Throw that) {
        super.visit(that);
        Tree.Expression e = that.getExpression();
        if (e!=null) {
            ProducedType et = e.getTypeModel();
            if (!et.isUnknown()) {
                checkAssignable(et, unit.getExceptionDeclaration().getType(), 
                        e, "thrown expression must be an exception");
            }
        }
    }
    
    @Override public void visit(Tree.Return that) {
        super.visit(that);
        if (returnType==null) {
            //misplaced return statements are already handled by ControlFlowVisitor
            //missing return types declarations already handled by TypeVisitor
            //that.addError("could not determine expected return type");
        } 
        else {
            that.setDeclaration(returnDeclaration);
            Tree.Expression e = that.getExpression();
            String name = returnDeclaration.getName();
            if (name==null) name = "anonymous function";
            if (e==null) {
                if (!(returnType instanceof Tree.VoidModifier)) {
                    that.addError("a non-void method, function or getter must return a value: " +
                            name);
                }
            }
            else {
                ProducedType et = returnType.getTypeModel();
                ProducedType at = e.getTypeModel();
                if (returnType instanceof Tree.VoidModifier) {
                    that.addError("a void method, void function, setter, or class initializer may not return a value: " +
                            name);
                }
                else if (returnType instanceof Tree.LocalModifier) {
                    inferReturnType(et, at);
                }
                else {
                    if (!et.isUnknown() && !at.isUnknown()) {
                        checkAssignable(at, et, that.getExpression(), 
                                "returned expression must be assignable to return type of " +
                                        name, 2100);
                    }
                }
            }
        }
    }

    private void inferReturnType(ProducedType et, ProducedType at) {
        if (at!=null) {
            at = unit.denotableType(at);
            if (et==null || et.isSubtypeOf(at)) {
                returnType.setTypeModel(at);
            }
            else {
                if (!at.isSubtypeOf(et)) {
                    UnionType ut = new UnionType(unit);
                    List<ProducedType> list = new ArrayList<ProducedType>();
                    addToUnion(list, et);
                    addToUnion(list, at);
                    ut.setCaseTypes(list);
                    returnType.setTypeModel( ut.getType() );
                }
            }
        }
    }
    
    /*@Override public void visit(Tree.OuterExpression that) {
        that.getPrimary().visit(this);
        ProducedType pt = that.getPrimary().getTypeModel();
        if (pt!=null) {
            if (pt.getDeclaration() instanceof ClassOrInterface) {
                that.setTypeModel(getOuterType(that, (ClassOrInterface) pt.getDeclaration()));
                //TODO: some kind of MemberReference
            }
            else {
                that.addError("can't use outer on a type parameter");
            }
        }
    }*/
    
    ProducedType unwrap(ProducedType pt, Tree.QualifiedMemberOrTypeExpression mte) {
        ProducedType result;
        Tree.MemberOperator op = mte.getMemberOperator();
        Tree.Primary p = mte.getPrimary();
        if (op instanceof Tree.SafeMemberOp)  {
            checkOptional(pt, p, p);
            result = unit.getDefiniteType(pt);
        }
        else if (op instanceof Tree.SpreadOp) {
            if (unit.isIterableType(pt)) {
                result = unit.getIteratedType(pt);
            }
            else {
                p.addError("expression must be of iterable type: " +
                        pt.getProducedTypeName(unit) + 
                        " is not a subtype of Iterable");
                result = pt;
            }
        }
        else {
            result = pt;
        }
        if (result==null) {
            result = new UnknownType(mte.getUnit()).getType();
        }
        return result;
    }
    
    ProducedType wrap(ProducedType pt, ProducedType receivingType, 
            Tree.QualifiedMemberOrTypeExpression mte) {
        Tree.MemberOperator op = mte.getMemberOperator();
        if (op instanceof Tree.SafeMemberOp)  {
            return unit.getOptionalType(pt);
        }
        else if (op instanceof Tree.SpreadOp) {
            //note: the following is nice, even though
            //      it is not actually blessed by the
            //      language spec!
            return receivingType.getSupertype(unit.getSequenceDeclaration())==null ?
                    unit.getSequentialType(pt) :
                    unit.getSequenceType(pt);
        }
        else {
            return pt;
        }
    }
    
    @Override public void visit(Tree.InvocationExpression that) {
        
        Tree.Primary p = that.getPrimary();
        
        boolean isDirectInvocation = p instanceof Tree.MemberOrTypeExpression;
        if (isDirectInvocation) {
            Tree.MemberOrTypeExpression mte = (Tree.MemberOrTypeExpression) p;
            mte.setDirectlyInvoked(true);
        }
        
        Tree.PositionalArgumentList pal = that.getPositionalArgumentList();
        if (pal!=null) {
            pal.visit(this);
            if (isDirectInvocation) {
                //set up the "signature" on the primary
                //so that we can resolve the the right 
                //overloaded declaration
                List<ProducedType> sig = new ArrayList<ProducedType>();
                List<Tree.PositionalArgument> args = pal.getPositionalArguments();
                for (Tree.PositionalArgument pa: args) {
                    sig.add(pa.getTypeModel());
                }
                Tree.MemberOrTypeExpression mte = (Tree.MemberOrTypeExpression) p;
                mte.setSignature(sig);
                mte.setEllipsis(hasSpreadArgument(args));
            }
        }
        
        Tree.NamedArgumentList nal = that.getNamedArgumentList();
        if (nal!=null) {
            nal.visit(this);
        }
        
        p.visit(this);
        
        visitInvocation(that);
    }

    private void visitInvocation(Tree.InvocationExpression that) {
        Tree.Primary pr = that.getPrimary();
        if (pr==null) {
            that.addError("malformed invocation expression");
        }
        else if (pr instanceof Tree.StaticMemberOrTypeExpression) {
            Tree.StaticMemberOrTypeExpression mte = (Tree.StaticMemberOrTypeExpression) pr;
            Declaration dec = mte.getDeclaration();
            if ( mte.getTarget()==null && dec instanceof Functional && 
                    mte.getTypeArguments() instanceof Tree.InferredTypeArguments ) {
                List<ProducedType> typeArgs = getInferedTypeArguments(that, (Functional) dec);
                mte.getTypeArguments().setTypeModels(typeArgs);
                if (pr instanceof Tree.BaseTypeExpression) {
                    visitBaseTypeExpression((Tree.BaseTypeExpression) pr,
                            (TypeDeclaration) dec, typeArgs, mte.getTypeArguments());
                }
                else if (pr instanceof Tree.QualifiedTypeExpression) {
                    visitQualifiedTypeExpression((Tree.QualifiedTypeExpression) pr,
                            ((Tree.QualifiedTypeExpression) pr).getPrimary().getTypeModel(),
                            (TypeDeclaration) dec, typeArgs, mte.getTypeArguments());
                }
                else if (pr instanceof Tree.BaseMemberExpression) {
                    visitBaseMemberExpression((Tree.BaseMemberExpression) pr,
                            (TypedDeclaration) dec, typeArgs, mte.getTypeArguments());
                }
                else if (pr instanceof Tree.QualifiedMemberExpression) {
                    visitQualifiedMemberExpression((Tree.QualifiedMemberExpression) pr,
                            ((Tree.QualifiedMemberExpression) pr).getPrimary().getTypeModel(),
                            (TypedDeclaration) dec, typeArgs, mte.getTypeArguments());
                }
            }
            visitInvocation(that, mte.getTarget());
        }
        else if (pr instanceof Tree.ExtendedTypeExpression) {
            visitInvocation(that, ((Tree.ExtendedTypeExpression) pr).getTarget());
        }
        else {
            visitInvocation(that, null);
        }
    }

    private List<ProducedType> getInferedTypeArguments(Tree.InvocationExpression that, 
            Functional dec) {
        List<ProducedType> typeArgs = new ArrayList<ProducedType>();
        if (!dec.getParameterLists().isEmpty()) {
            ParameterList parameters = dec.getParameterLists().get(0);
            for (TypeParameter tp: dec.getTypeParameters()) {
                typeArgs.add(constrainInferredType(dec, tp, 
                        inferTypeArgument(that, that.getPrimary().getTypeModel(), 
                                tp, parameters)));
            }
        }
        return typeArgs;
    }

    private ProducedType constrainInferredType(Functional dec,
            TypeParameter tp, ProducedType ta) {
        List<ProducedType> list = new ArrayList<ProducedType>();
        addToIntersection(list, ta, unit);
        //Intersect the inferred type with any 
        //upper bound constraints on the type. This
        //helps with cases like passing an arg of
        //type String? to a parameter of type T?
        for (ProducedType st: tp.getSatisfiedTypes()) {
            //TODO: st.getProducedType(receiver, dec, typeArgs);
            if (//if the upper bound is a type parameter, ignore it
                !dec.getTypeParameters().contains(st.getDeclaration()) &&
                (st.getQualifyingType()==null ||
                !dec.getTypeParameters().contains(st.getQualifyingType().getDeclaration())) &&
                //TODO: remove this awful hack that 
                //tries to work around the possibility 
                //that a type parameter appears in the 
                //upper bound!
                !st.getDeclaration().isParameterized() &&
                (st.getQualifyingType()==null || 
                !st.getQualifyingType().getDeclaration().isParameterized())) {
                addToIntersection(list, st, unit);
            }
        }
        IntersectionType it = new IntersectionType(unit);
        it.setSatisfiedTypes(list);
        ProducedType type = it.canonicalize().getType();
        return type;
    }

    private ProducedType inferTypeArgument(Tree.InvocationExpression that,
            ProducedReference pr, TypeParameter tp, ParameterList parameters) {
        List<ProducedType> inferredTypes = new ArrayList<ProducedType>();
        if (that.getPositionalArgumentList()!=null) {
            inferTypeArgumentFromPositionalArgs(tp, parameters, pr, that.getPositionalArgumentList(), inferredTypes);
        }
        else if (that.getNamedArgumentList()!=null) {
            inferTypeArgumentFromNamedArgs(tp, parameters, pr, that.getNamedArgumentList(), inferredTypes);
        }
        return formUnionOrIntersection(tp, inferredTypes);
    }

    private void inferTypeArgumentFromNamedArgs(TypeParameter tp, ParameterList parameters,
            ProducedReference pr, Tree.NamedArgumentList args, 
            List<ProducedType> inferredTypes) {
        Set<Parameter> foundParameters = new HashSet<Parameter>();
        for (Tree.NamedArgument arg: args.getNamedArguments()) {
            inferTypeArgFromNamedArg(arg, tp, pr, parameters, inferredTypes, foundParameters);
        }
        Tree.SequencedArgument sa = args.getSequencedArgument();
        if (sa!=null) {
            Parameter sp = getUnspecifiedParameter(null, parameters, foundParameters);
            if (sp!=null) {
                inferTypeArgFromSequencedArg(sa, tp, sp, inferredTypes);
            }
        }    
    }

    private void inferTypeArgFromSequencedArg(Tree.SequencedArgument sa, TypeParameter tp,
            Parameter sp, List<ProducedType> inferredTypes) {
        List<Tree.PositionalArgument> args = sa.getPositionalArguments();
        ProducedType att = getTupleType(args, false);
        ProducedType spt = sp.getType();
        addToUnionOrIntersection(tp,inferredTypes, inferTypeArg(tp, spt, att,
                new ArrayList<TypeParameter>()));
    }

    private void inferTypeArgFromNamedArg(Tree.NamedArgument arg, TypeParameter tp,
            ProducedReference pr, ParameterList parameters, 
            List<ProducedType> inferredTypes, Set<Parameter> foundParameters) {
        ProducedType type = null;
        if (arg instanceof Tree.SpecifiedArgument) {
            Tree.Expression e = ((Tree.SpecifiedArgument) arg).getSpecifierExpression()
                            .getExpression();
            if (e!=null) {
                type = e.getTypeModel();
            }
        }
        else if (arg instanceof Tree.TypedArgument) {
            //copy/pasted from checkNamedArgument()
            Tree.TypedArgument ta = (Tree.TypedArgument) arg;
            type = ta.getDeclarationModel().getProducedTypedReference(null,
                    //assuming an argument can't have type params 
                    Collections.<ProducedType>emptyList()).getFullType();
        }
        if (type!=null) {
            Parameter parameter = getMatchingParameter(parameters, arg, foundParameters);
            if (parameter!=null) {
                foundParameters.add(parameter);
                ProducedType pt = pr.getTypedParameter(parameter)
                        .getFullType();
//              if (parameter.isSequenced()) pt = unit.getIteratedType(pt);
                addToUnionOrIntersection(tp,inferredTypes, inferTypeArg(tp, pt, type, 
                        new ArrayList<TypeParameter>()));
            }
        }
    }

    private void inferTypeArgumentFromPositionalArgs(TypeParameter tp, ParameterList parameters,
            ProducedReference pr, Tree.PositionalArgumentList pal, 
            List<ProducedType> inferredTypes) {
        List<Parameter> params = parameters.getParameters();
        for (int i=0; i<params.size(); i++) {
            Parameter parameter = params.get(i);
            List<Tree.PositionalArgument> args = pal.getPositionalArguments();
            if (args.size()>i) {
                Tree.PositionalArgument a = args.get(i);
                ProducedType at = a.getTypeModel();
                if (a instanceof Tree.SpreadArgument) {
                    at = spreadType(at, unit, true);
                    ProducedType ptt = getParameterTypesAsTupleType(params.subList(i, params.size()), pr);
                    addToUnionOrIntersection(tp, inferredTypes, inferTypeArg(tp, ptt, at, 
                            new ArrayList<TypeParameter>()));
                }
                else if (a instanceof Tree.Comprehension) {
                    if (parameter.isSequenced()) {
                        inferTypeArgFromComprehension(tp, parameter, ((Tree.Comprehension) a), inferredTypes);
                    }
                }
                else {
                    if (parameter.isSequenced()) {
                        inferTypeArgFromPositionalArgs(tp, parameter, args.subList(i, args.size()), inferredTypes);
                        break;
                    }
                    else {
                        ProducedType pt = pr.getTypedParameter(parameter)
                                .getFullType();
                        addToUnionOrIntersection(tp, inferredTypes, inferTypeArg(tp, pt, at, 
                                new ArrayList<TypeParameter>()));
                    }
                }
            }
        }
    }

    private void inferTypeArgFromPositionalArgs(TypeParameter tp, Parameter parameter,
            List<Tree.PositionalArgument> args, List<ProducedType> inferredTypes) {
        for (int k=0; k<args.size(); k++) {
            Tree.PositionalArgument sa = args.get(k);
            ProducedType sat = sa.getTypeModel();
            if (sat!=null) {
                ProducedType pt = parameter.getType();
                if (sa instanceof Tree.SpreadArgument) {
                    sat = spreadType(sat, unit, true);
                    addToUnionOrIntersection(tp, inferredTypes, inferTypeArg(tp, pt, sat,
                            new ArrayList<TypeParameter>()));
                }
                else {
                    ProducedType spt = unit.getIteratedType(pt);
                    addToUnionOrIntersection(tp, inferredTypes, inferTypeArg(tp, spt, sat,
                            new ArrayList<TypeParameter>()));
                }
            }
        }
    }
    
    private void inferTypeArgFromComprehension(TypeParameter tp, Parameter parameter,
            Tree.Comprehension c, List<ProducedType> inferredTypes) {
            ProducedType sat = c.getTypeModel();
            if (sat!=null) {
                ProducedType pt = parameter.getType();
                ProducedType spt = unit.getIteratedType(pt);
                addToUnionOrIntersection(tp, inferredTypes, inferTypeArg(tp, spt, sat,
                        new ArrayList<TypeParameter>()));
            }
    }
    
    private ProducedType formUnionOrIntersection(TypeParameter tp,
            List<ProducedType> inferredTypes) {
        if (tp.isContravariant()) {
            return formIntersection(inferredTypes);
        }
        else {
            return formUnion(inferredTypes);
        }
    }
    
    private ProducedType unionOrIntersection(TypeParameter tp,
            List<ProducedType> inferredTypes) {
        if (inferredTypes.isEmpty()) {
            return null;
        }
        else {
            return formUnionOrIntersection(tp, inferredTypes);
        }
    }
    
    private void addToUnionOrIntersection(TypeParameter tp, List<ProducedType> list, ProducedType pt) {
        if (tp.isContravariant()) {
            addToIntersection(list, pt, unit);
        }
        else {
            addToUnion(list, pt);
        }
    }

    private ProducedType union(List<ProducedType> types) {
        if (types.isEmpty()) {
            return null;
        }
        return formUnion(types);
    }

    private ProducedType intersection(List<ProducedType> types) {
        if (types.isEmpty()) {
            return null;
        }
        return formIntersection(types);
    }

    private ProducedType formUnion(List<ProducedType> types) {
        UnionType ut = new UnionType(unit);
        ut.setCaseTypes(types);
        return ut.getType();
    }
    
    private ProducedType formIntersection(List<ProducedType> types) {
        IntersectionType it = new IntersectionType(unit);
        it.setSatisfiedTypes(types);
        return it.canonicalize().getType();
    }
    
    private ProducedType inferTypeArg(TypeParameter tp, ProducedType paramType,
            ProducedType argType, List<TypeParameter> visited) {
        if (paramType!=null && argType!=null) {
            if (paramType.getDeclaration() instanceof TypeParameter &&
                    paramType.getDeclaration().equals(tp)) {
                return unit.denotableType(argType);
            }
            else if (paramType.getDeclaration() instanceof TypeParameter) {
                TypeParameter tp2 = (TypeParameter) paramType.getDeclaration();
                if (!visited.contains(tp2)) {
                    visited.add(tp2);
                    List<ProducedType> list = new ArrayList<ProducedType>();
                    for (ProducedType pt: tp2.getSatisfiedTypes()) {
                        addToUnionOrIntersection(tp, list, inferTypeArg(tp, pt, argType, visited));
                        ProducedType st = argType.getSupertype(pt.getDeclaration());
                        if (st!=null) {
                            for (int j=0; j<pt.getTypeArgumentList().size(); j++) {
                                if (st.getTypeArgumentList().size()>j) {
                                    addToUnionOrIntersection(tp, list, inferTypeArg(tp, 
                                            pt.getTypeArgumentList().get(j), 
                                            st.getTypeArgumentList().get(j), 
                                            visited));
                                }
                            }
                        }
                    }
                    return unionOrIntersection(tp, list);
                }
                else {
                    return null;
                }
            }
            else if (paramType.getDeclaration() instanceof UnionType) {
                List<ProducedType> list = new ArrayList<ProducedType>();
                //If there is more than one type parameter in
                //the union, ignore this union when inferring 
                //types. TODO: this is a bit adhoc
                boolean found = false;
                for (ProducedType ct: paramType.getDeclaration().getCaseTypes()) {
                    if (ct.getDeclaration() instanceof TypeParameter) {
                        if (found) return null;
                        found = true;
                    }
                }
                for (ProducedType ct: paramType.getDeclaration().getCaseTypes()) {
                    //TODO: should this be addToIntersectionOrUnion()?!
                    addToIntersection(list, inferTypeArg(tp, 
                            ct.substitute(paramType.getTypeArguments()), 
                            argType, visited), unit);
                }
                //TODO: if so, then this should be intersectionOrUnion()
                return intersection(list);
            }
            else if (paramType.getDeclaration() instanceof IntersectionType) {
                List<ProducedType> list = new ArrayList<ProducedType>();
                for (ProducedType ct: paramType.getDeclaration().getSatisfiedTypes()) {
                    addToUnionOrIntersection(tp, list, inferTypeArg(tp, 
                            ct.substitute(paramType.getTypeArguments()), 
                            argType, visited));
                }
                return unionOrIntersection(tp,list);
            }
            else if (argType.getDeclaration() instanceof UnionType) {
                List<ProducedType> list = new ArrayList<ProducedType>();
                for (ProducedType ct: argType.getDeclaration().getCaseTypes()) {
                    addToUnion(list, inferTypeArg(tp, paramType, 
                            ct.substitute(paramType.getTypeArguments()), 
                            visited));
                }
                return union(list);
            }
            else if (argType.getDeclaration() instanceof IntersectionType) {
                List<ProducedType> list = new ArrayList<ProducedType>();
                for (ProducedType ct: argType.getDeclaration().getSatisfiedTypes()) {
                    addToIntersection(list, inferTypeArg(tp, paramType, 
                            ct.substitute(paramType.getTypeArguments()), 
                            visited), unit);
                }
                return intersection(list);
            }
            else {
                ProducedType st = argType.getSupertype(paramType.getDeclaration());
                if (st!=null) {
                    List<ProducedType> list = new ArrayList<ProducedType>();
                    if (paramType.getQualifyingType()!=null && 
                            st.getQualifyingType()!=null) {
                        addToUnionOrIntersection(tp, list, inferTypeArg(tp, 
                                    paramType.getQualifyingType(), 
                                    st.getQualifyingType(), 
                                    visited));
                    }
                    for (int j=0; j<paramType.getTypeArgumentList().size(); j++) {
                        if (st.getTypeArgumentList().size()>j) {
                            addToUnionOrIntersection(tp, list, inferTypeArg(tp, 
                                    paramType.getTypeArgumentList().get(j), 
                                    st.getTypeArgumentList().get(j), 
                                    visited));
                        }
                    }
                    return unionOrIntersection(tp, list);
                }
                else {
                    return null;
                }
            }
        }
        else {
            return null;
        }
    }
    
    private ProducedType nonemptyArgs(ProducedType args) {
        return unit.isEmptyType(args) ? 
                unit.getNonemptyType(args) : args;
    }
    
    private List<ProducedType> getTupleElementTypes(ProducedType args) {
        if (args!=null) {
            ProducedType tst = nonemptyArgs(args).getSupertype(unit.getTupleDeclaration());
            if (tst!=null) {
                List<ProducedType> tal = tst.getTypeArgumentList();
                if (tal.size()>=3) {
                    List<ProducedType> result = getTupleElementTypes(tal.get(2));
                    result.add(0, tal.get(1));
                    return result;
                }
            }
            else if (args.getSupertype(unit.getEmptyDeclaration())!=null) {
                return new LinkedList<ProducedType>();
            }
            else if (args.getSupertype(unit.getSequentialDeclaration())!=null) {
                LinkedList<ProducedType> sequenced = new LinkedList<ProducedType>();
                sequenced.add(args);
                return sequenced;
            }
        }
        LinkedList<ProducedType> unknown = new LinkedList<ProducedType>();
        unknown.add(new UnknownType(unit).getType());
        return unknown;
    }
    
    private boolean isTupleLengthUnbounded(ProducedType args) {
        if (args!=null) {
            ProducedType tst = nonemptyArgs(args).getSupertype(unit.getTupleDeclaration());
            if (tst!=null) {
                List<ProducedType> tal = tst.getTypeArgumentList();
                if (tal.size()>=3) {
                    return isTupleLengthUnbounded(tal.get(2));
                }
            }
            else if (args.getSupertype(unit.getEmptyDeclaration())!=null) {
                return false;
            }
            else if (args.getSupertype(unit.getSequentialDeclaration())!=null) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isTupleVariantAtLeastOne(ProducedType args) {
        if (args!=null) {
            ProducedType tst = nonemptyArgs(args).getSupertype(unit.getTupleDeclaration());
            if (tst!=null) {
                List<ProducedType> tal = tst.getTypeArgumentList();
                if (tal.size()>=3) {
                    return isTupleVariantAtLeastOne(tal.get(2));
                }
            }
            else if (args.getSupertype(unit.getEmptyDeclaration())!=null) {
                return false;
            }
            else if (args.getSupertype(unit.getSequenceDeclaration())!=null) {
                return true;
            }
            else if (args.getSupertype(unit.getSequentialDeclaration())!=null) {
                return false;
            }
        }
        return false;
    }
    
    private int getTupleMinimumLength(ProducedType args) {
        if (args!=null) {
            if (unit.isEmptyType(args)) {
                return 0;
            }
            ProducedType tst = args.getSupertype(unit.getTupleDeclaration());
            if (tst!=null) {
                List<ProducedType> tal = tst.getTypeArgumentList();
                if (tal.size()>=3) {
                    return getTupleMinimumLength(tal.get(2))+1;
                }
            }
            else if (args.getSupertype(unit.getEmptyDeclaration())!=null) {
                return 0;
            }
            else if (args.getSupertype(unit.getSequentialDeclaration())!=null) {
                return 0;
            }
        }
        return 0;
    }

    private void visitInvocation(Tree.InvocationExpression that, ProducedReference prf) {
        if (prf==null || !prf.isFunctional()) {
            ProducedType pt = that.getPrimary().getTypeModel();
            if (pt!=null && !pt.isUnknown()) {
                if (checkCallable(pt, that.getPrimary(), 
                        "invoked expression must be callable")) {
                    List<ProducedType> typeArgs = pt.getSupertype(unit.getCallableDeclaration())
                            .getTypeArgumentList();
                    if (!typeArgs.isEmpty()) {
                        that.setTypeModel(typeArgs.get(0));
                    }
                    //typecheck arguments using the type args of Callable
                    if (typeArgs.size()>=2) {
                        ProducedType tt = typeArgs.get(1);
                        checkIndirectInvocationArguments(that, 
                                getTupleElementTypes(tt),
                                isTupleLengthUnbounded(tt),
                                getTupleMinimumLength(tt));
                    }
                }
            }
        }
        else {
            Tree.MemberOrTypeExpression mte = (Tree.MemberOrTypeExpression) that.getPrimary();
            Functional dec = (Functional) mte.getDeclaration();
            if (!(that.getPrimary() instanceof Tree.ExtendedTypeExpression)) {
                if (dec instanceof Class && ((Class) dec).isAbstract()) {
                    that.addError("abstract class may not be instantiated: " + dec.getName(unit));
                }
            }
            if (that.getNamedArgumentList()!=null && 
                    dec.isAbstraction()) {
                //TODO: this is not really right - it's the fact 
                //      that we're calling Java and don't have
                //      meaningful parameter names that is the
                //      real problem, not the overload
                that.addError("overloaded declarations may not be called using named arguments: " +
                        dec.getName(unit));
            }
            //that.setTypeModel(prf.getType());
            ProducedType ct = that.getPrimary().getTypeModel();
            if (ct!=null && !ct.getTypeArgumentList().isEmpty()) {
                //pull the return type out of the Callable
                that.setTypeModel(ct.getTypeArgumentList().get(0));
            }
            if (that.getNamedArgumentList() != null) {
                List<ParameterList> parameterLists = dec.getParameterLists();
                if(!parameterLists.isEmpty()
                        && !parameterLists.get(0).isNamedParametersSupported()) {
                    that.addError("named invocations of Java methods not supported");
                }
            }
            if (dec.isAbstraction()) {
                //nothing to check the argument types against
                //that.addError("no matching overloaded declaration");
            }
            else {
                //typecheck arguments using the parameter list
                //of the target declaration
                checkInvocationArguments(that, prf, dec);
            }
        }
    }

    private void checkInvocationArguments(Tree.InvocationExpression that,
            ProducedReference prf, Functional dec) {
        List<ParameterList> pls = dec.getParameterLists();
        if (pls.isEmpty()) {
            if (dec instanceof TypeDeclaration) {
                that.addError("type cannot be instantiated: " + 
                        dec.getName(unit));
            }
            else {
                that.addError("member cannot be invoked: " +
                        dec.getName(unit));
            }
        }
        else /*if (!dec.isOverloaded())*/ {
            ParameterList pl = pls.get(0);            
            Tree.PositionalArgumentList args = that.getPositionalArgumentList();
            if (args!=null) {
                checkPositionalArguments(pl, prf, args);
            }
            Tree.NamedArgumentList namedArgs = that.getNamedArgumentList();
            if (namedArgs!=null) {
                if (pl.isNamedParametersSupported()) {
                    namedArgs.getNamedArgumentList().setParameterList(pl);
                    checkNamedArguments(pl, prf, namedArgs);
                }
            }
        }
    }
    
    private ProducedType getParameterTypesAsTupleType(List<Parameter> params,
            ProducedReference pr) {
        List<ProducedType> paramTypes = new ArrayList<ProducedType>();
        int max = params.size()-1;
        int firstDefaulted = -1;
        boolean sequenced = false;
        for (int i=0; i<=max; i++) {
            Parameter p = params.get(i);
            ProducedType ft = pr.getTypedParameter(p).getFullType();
            if (firstDefaulted<0 && p.isDefaulted()) {
                firstDefaulted = i;
            }
            if (i==max && p.isSequenced()) {
                sequenced = true;
                if (ft!=null) {
                    ft = unit.getIteratedType(ft);
                }
            }
            paramTypes.add(ft);
        }
        return unit.getTupleType(paramTypes, sequenced, false, 
                firstDefaulted);
    }
    
    /*private void checkSpreadArgumentSequential(Tree.SpreadArgument arg,
            ProducedType argTuple) {
        if (!unit.isSequentialType(argTuple)) {
            arg.addError("spread argument expression is not sequential: " +
                    argTuple.getProducedTypeName(unit) + " is not a sequence type");
        }
    }*/
    
    private void checkNamedArguments(ParameterList pl, ProducedReference pr, 
            Tree.NamedArgumentList nal) {
        List<Tree.NamedArgument> na = nal.getNamedArguments();        
        Set<Parameter> foundParameters = new HashSet<Parameter>();
        
        for (Tree.NamedArgument a: na) {
            checkNamedArg(a, pl, pr, foundParameters);
        }
        
        Tree.SequencedArgument sa = nal.getSequencedArgument();
        if (sa!=null) {
            checkSequencedArg(sa, pl, pr, foundParameters);        
        }
        else {
            Parameter sp = getUnspecifiedParameter(pr, pl, foundParameters);
            foundParameters.add(sp);
        }
            
        for (Parameter p: pl.getParameters()) {
            if (!foundParameters.contains(p) && 
                    !p.isDefaulted() && !p.isSequenced()) {
                nal.addError("missing named argument to parameter " + 
                        p.getName() + " of " + pr.getDeclaration().getName(unit));
            }
        }
    }
    
    private void checkSequencedArg(Tree.SequencedArgument sa, ParameterList pl,
            ProducedReference pr, Set<Parameter> foundParameters) {
        Parameter sp = getUnspecifiedParameter(pr, pl, foundParameters);
        if (sp==null) {
            sa.addError("all iterable parameters specified by named argument list: " + 
                    pr.getDeclaration().getName(unit) +
                    " does not declare any additional parameters of type Iterable");
        }
        else {
            if (!foundParameters.add(sp)) {
                sa.addError("duplicate argument for parameter: " +
                        sp + " of " + pr.getDeclaration().getName(unit));
            }
            else if (!dynamic && isTypeUnknown(sp.getType())) {
                sa.addError("parameter type could not be determined: " + 
                        sp.getName() + " of " + sp.getDeclaration().getName(unit));
            }
            checkSequencedArgument(sa, pr, sp);
        }
    }

    private void checkNamedArg(Tree.NamedArgument a, ParameterList pl,
            ProducedReference pr, Set<Parameter> foundParameters) {
        Parameter p = getMatchingParameter(pl, a, foundParameters);
        if (p==null) {
            if (a.getIdentifier()==null) {
                a.addError("all parameters specified by named argument list: " + 
                        pr.getDeclaration().getName(unit) +
                        " does not declare any additional parameters");
            }
            else {
                a.addError("no matching parameter for named argument " + 
                        name(a.getIdentifier()) + " declared by " + 
                        pr.getDeclaration().getName(unit), 101);
            }
        }
        else {
            if (!foundParameters.add(p)) {
                a.addError("duplicate argument for parameter: " +
                        p + " of " + pr.getDeclaration().getName(unit));
            }
            else if (!dynamic && isTypeUnknown(p.getType())) {
                a.addError("parameter type could not be determined: " + 
                        p.getName() + " of " + p.getDeclaration().getName(unit));
            }
            checkNamedArgument(a, pr, p);
        }
    }

    private void checkNamedArgument(Tree.NamedArgument a, ProducedReference pr, 
            Parameter p) {
        a.setParameter(p);
        ProducedType argType = null;
        if (a instanceof Tree.SpecifiedArgument) {
            Tree.SpecifiedArgument sa = (Tree.SpecifiedArgument) a;
            Tree.Expression e = sa.getSpecifierExpression().getExpression();
            if (e!=null) {
                argType = e.getTypeModel();
            }
        }
        else if (a instanceof Tree.TypedArgument) {
            Tree.TypedArgument ta = (Tree.TypedArgument) a;
            argType = ta.getDeclarationModel().getProducedTypedReference(null,
                     //assuming an argument can't have type params 
                    Collections.<ProducedType>emptyList()).getFullType();
            //argType = ta.getType().getTypeModel();
            checkArgumentToVoidParameter(p, ta);
        }
        ProducedType pt = pr.getTypedParameter(p).getFullType();
//      if (p.isSequenced()) pt = unit.getIteratedType(pt);
        if (argType!=null && pt!=null && 
                !argType.isUnknown() && !pt.isUnknown()) {
            checkAssignable(argType, pt, a,
                    "named argument must be assignable to parameter " + 
                            p.getName() + " of " + pr.getDeclaration().getName(unit), 
                            2100);
        }
    }

    private void checkArgumentToVoidParameter(Parameter p, Tree.TypedArgument ta) {
        if (ta instanceof Tree.MethodArgument) {
            Tree.MethodArgument ma = (Tree.MethodArgument) ta;
            Tree.SpecifierExpression se = ma.getSpecifierExpression();
            if (se!=null && se.getExpression()!=null) {
                Tree.Type t = ta.getType();
                // if the argument is explicitly declared 
                // using the function modifier, it should 
                // be allowed, even if the parameter is 
                // declared void
                //TODO: what is a better way to check that 
                //      this is really the "shortcut" form
                if (t instanceof Tree.FunctionModifier && 
                        t.getToken()==null &&
                    p instanceof FunctionalParameter &&
                        ((FunctionalParameter) p).isDeclaredVoid() &&
                    !isSatementExpression(se.getExpression())) {
                    ta.addError("functional parameter is declared void so argument may not evaluate to a value: " +
                            p.getName());
                }
            }
        }
    }
    
    private void checkSequencedArgument(Tree.SequencedArgument sa, ProducedReference pr, 
            Parameter p) {
        sa.setParameter(p);
        List<Tree.PositionalArgument> args = sa.getPositionalArguments();
        ProducedType paramType = pr.getTypedParameter(p).getFullType();
        ProducedType att = getTupleType(args, false)
                .getSupertype(unit.getIterableDeclaration());
        if (att!=null && paramType!=null && 
                !att.isUnknown() && !paramType.isUnknown()) {
            checkAssignable(att, paramType, sa, 
                    "iterable arguments must be assignable to iterable parameter " + 
                            p.getName() + " of " + pr.getDeclaration().getName(unit));
        }
    }
    
    private Parameter getMatchingParameter(ParameterList pl, Tree.NamedArgument na, 
            Set<Parameter> foundParameters) {
        String name = name(na.getIdentifier());
        if (MISSING_NAME.equals(name)) {
            for (Parameter p: pl.getParameters()) {
                if (!foundParameters.contains(p)) {
                    Tree.Identifier node = new Tree.Identifier(null);
                    node.setScope(na.getScope());
                    node.setUnit(na.getUnit());
                    node.setText(p.getName());
                    na.setIdentifier(node);
                    return p;
                }
            }
        }
        else {
            for (Parameter p: pl.getParameters()) {
                if (p.getName().equals(name)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Parameter getUnspecifiedParameter(ProducedReference pr,
            ParameterList pl, Set<Parameter> foundParameters) {
        for (Parameter p: pl.getParameters()) {
            ProducedType t = pr==null ? 
                    p.getType() : 
                    pr.getTypedParameter(p).getFullType();
            if (t!=null &&!foundParameters.contains(p) &&
                    t.getDeclaration() instanceof Interface &&
                    t.getDeclaration().equals(unit.getIterableDeclaration())) {
                return p;
            }
        }
        return null;
    }
    
    private void checkPositionalArguments(ParameterList pl, ProducedReference pr, 
            Tree.PositionalArgumentList pal) {
        List<Tree.PositionalArgument> args = pal.getPositionalArguments();
        List<Parameter> params = pl.getParameters();
        for (int i=0; i<params.size(); i++) {
            Parameter p = params.get(i);
            if (i>=args.size()) {
                if (!p.isDefaulted() && !p.isSequenced()) {
                    pal.addError("missing argument to required parameter " + 
                            p.getName() + " of " + pr.getDeclaration().getName(unit));
                }
            } 
            else {
                Tree.PositionalArgument a = args.get(i);
                if (!dynamic && isTypeUnknown(p.getType())) {
                    a.addError("parameter type could not be determined: " + 
                            p.getName() + " of " + p.getDeclaration().getName(unit));
                }
                if (a instanceof Tree.SpreadArgument) {
                    checkSpreadArgument(pr, p, a, 
                            (Tree.SpreadArgument) a, 
                            params.subList(i, params.size()));
                    break;
                }
                else if (a instanceof Tree.Comprehension) {
                    if (p.isSequenced()) {
                        checkComprehensionPositionalArgument(p, pr, 
                                (Tree.Comprehension) a);
                    }
                    else {
                        a.addError("not a sequenced parameter: parameter " + 
                                p.getName() + " of " + pr.getDeclaration().getName());
                    }
                    break;
                }
                else {
                    if (p.isSequenced()) {
                        checkSequencedPositionalArgument(p, pr, 
                                args.subList(i, args.size()));
                        return; //Note: early return!
                    }
                    else {
                        checkPositionalArgument(p, pr, (Tree.ListedArgument) a);
                    }
                }
            }
        }
        
        for (int i=params.size(); i<args.size(); i++) {
            args.get(i).addError("no matching parameter declared by " +
                    pr.getDeclaration().getName(unit) + ": " + 
                    pr.getDeclaration().getName(unit) + " has " + 
                    params.size() + " parameters", 2000);
        }
    
    }

    private void checkSpreadArgument(ProducedReference pr, Parameter p,
            Tree.PositionalArgument a, Tree.SpreadArgument arg,
            List<Parameter> psl) {
        a.setParameter(p);
        ProducedType at = spreadType(arg.getTypeModel(), unit, true);
        //checkSpreadArgumentSequential(arg, at);
        ProducedType ptt = getParameterTypesAsTupleType(psl, pr);
        if (at!=null && ptt!=null && 
                !at.isUnknown() && !ptt.isUnknown()) {
            checkAssignable(at, ptt, arg, 
                    "spread argument not assignable to parameter types");
        }
    }
    
    private void checkIndirectInvocationArguments(Tree.InvocationExpression that, 
            List<ProducedType> paramTypes, boolean sequenced, int firstDefaulted) {
        
        if (that.getNamedArgumentList()!=null) {
            that.addError("named arguments not supported for indirect invocations");
        }
        
        Tree.PositionalArgumentList pal = that.getPositionalArgumentList();
        if (pal!=null) {
            List<Tree.PositionalArgument> args = pal.getPositionalArguments();
            for (int i=0; i<paramTypes.size(); i++) {
                if (i>=args.size()) {
                    if (i<firstDefaulted && (!sequenced || i!=paramTypes.size()-1)) {
                        pal.addError("missing argument for required parameter " + i);
                    }
                }
                else {
                    Tree.PositionalArgument arg = args.get(i);
                    ProducedType at = arg.getTypeModel();
                    if (arg instanceof Tree.SpreadArgument) {
                        checkSpreadIndirectArgument((Tree.SpreadArgument) arg, 
                                paramTypes.subList(i, paramTypes.size()), 
                                sequenced, firstDefaulted, at);
                        break;
                    }
                    else if (arg instanceof Tree.Comprehension) {
                        ProducedType paramType = paramTypes.get(i);
                        if (sequenced && i==paramTypes.size()-1) {
                            checkSequencedIndirectArgument((Tree.Comprehension) arg, 
                                paramType);
                        }
                        else {
                            arg.addError("not a sequenced parameter: parameter " + i);
                        }
                        break;
                    }
                    else {
                        ProducedType paramType = paramTypes.get(i);
                        if (sequenced && i==paramTypes.size()-1) {
                            checkSequencedIndirectArgument(args.subList(i, args.size()), 
                                    paramType);
                            return; //Note: early return!
                        }
                        else if (at!=null && paramType!=null && 
                                !at.isUnknown() && !paramType.isUnknown()) {
                            checkAssignable(at, paramType, arg, 
                                    "argument must be assignable to parameter type");
                        }
                    }
                }
            }
    
            for (int i=paramTypes.size(); i<args.size(); i++) {
                args.get(i).addError("no matching parameter: function reference has " + 
                        paramTypes.size() + " parameters", 2000);
            }
    
        }
    }

    private void checkSpreadIndirectArgument(Tree.SpreadArgument sa,
            List<ProducedType> psl, boolean sequenced, int firstDefaulted,
            ProducedType at) {
        //checkSpreadArgumentSequential(sa, at);
        at = spreadType(at, unit, true);
        //TODO: this ultimately repackages the parameter
        //      information as a tuple - it would be
        //      better to just truncate the original
        //      tuple type we started with
        List<ProducedType> pts = new ArrayList<ProducedType>(psl);
        if (sequenced) {
            pts.set(pts.size()-1, 
                    unit.getIteratedType(pts.get(pts.size()-1)));
        }
        ProducedType ptt = unit.getTupleType(pts, sequenced, false, 
                firstDefaulted);
        if (at!=null && ptt!=null && 
                !at.isUnknown() && !ptt.isUnknown()) {
            checkAssignable(at, ptt, sa, 
                    "spread argument not assignable to parameter types");
        }
    }

    private void checkSequencedIndirectArgument(List<Tree.PositionalArgument> args, 
            ProducedType paramType) {
        ProducedType set = paramType==null ? null : unit.getIteratedType(paramType);
        for (int i=0; i<args.size(); i++) {
            Tree.PositionalArgument a = args.get(i);
            ProducedType at = a.getTypeModel();
            if (at!=null && set!=null && 
                    !at.isUnknown() && !set.isUnknown()) {
                if (a instanceof Tree.SpreadArgument) {
                    //checkSpreadArgumentSequential((Tree.SpreadArgument) a, at);
                    at = spreadType(at, unit, true);
                    checkAssignable(unit.getIteratedType(at), set, a,
                            "spread argument must be assignable to sequenced parameter ");
                    checkAssignable(at, paramType, a,
                            "spread argument must be assignable to sequenced parameter ");
                }
                else {
                    checkAssignable(at, set, a, 
                            "argument must be assignable to sequenced parameter");
                }
            }
        }
    }
    
    private void checkSequencedIndirectArgument(Tree.Comprehension c, 
            ProducedType paramType) {
        ProducedType at = c.getTypeModel();
        ProducedType set = paramType==null ? null : unit.getIteratedType(paramType);
        if (at!=null && set!=null && 
                !at.isUnknown() && !set.isUnknown()) {
            checkAssignable(at, set, c, 
                    "argument must be assignable to sequenced parameter");
        }
    }
    
    private void checkSequencedPositionalArgument(Parameter p, ProducedReference pr,
            List<Tree.PositionalArgument> args) {
        ProducedType paramType = pr.getTypedParameter(p).getFullType();
        ProducedType set = paramType==null ? null : unit.getIteratedType(paramType);
        for (int j=0; j<args.size(); j++) {
            Tree.PositionalArgument a = args.get(j);
            a.setParameter(p);
            ProducedType at = a.getTypeModel();
            if (at!=null && paramType!=null && 
                    !at.isUnknown() && !paramType.isUnknown()) {
                if (a instanceof Tree.SpreadArgument) {
                    at = spreadType(at, unit, true);
                    checkAssignable(at, paramType, a, 
                            "spread argument must be assignable to sequenced parameter " + 
                                    p.getName()+ " of " + pr.getDeclaration().getName(unit), 2101);
                }
                else {
                    checkAssignable(at, set, a, 
                            "argument must be assignable to sequenced parameter " + 
                                    p.getName()+ " of " + pr.getDeclaration().getName(unit), 2101);
                }
            }
        }
    }
    
    private void checkComprehensionPositionalArgument(Parameter p, ProducedReference pr,
            Tree.Comprehension c) {
        ProducedType paramType = pr.getTypedParameter(p).getFullType();
        c.setParameter(p);
        ProducedType at = c.getTypeModel();
        if (at!=null && paramType!=null && 
                !at.isUnknown() && !paramType.isUnknown()) {
            ProducedType set = paramType==null ? null : unit.getIteratedType(paramType);
            checkAssignable(at, set, c, 
                    "argument must be assignable to sequenced parameter " + 
                            p.getName()+ " of " + pr.getDeclaration().getName(unit), 2101);
        }
    }
    
    private boolean hasSpreadArgument(List<Tree.PositionalArgument> args) {
        int size = args.size();
        if (size>0) {
            return args.get(size-1) instanceof Tree.SpreadArgument;
        }
        else {
            return false;
        }
    }

    private void checkPositionalArgument(Parameter p, ProducedReference pr,
            Tree.ListedArgument a) {
        ProducedType paramType = pr.getTypedParameter(p).getFullType();
        a.setParameter(p);
        ProducedType at = a.getTypeModel();
        if (at!=null && paramType!=null && 
                !at.isUnknown() && !paramType.isUnknown()) {
            checkAssignable(at, paramType, a, 
                    "argument must be assignable to parameter " + 
                            p.getName() + " of " + pr.getDeclaration().getName(unit), 
                            2100);
        }
    }
    
    @Override public void visit(Tree.Comprehension that) {
        super.visit(that);
        that.setTypeModel(that.getForComprehensionClause().getTypeModel());
    }

    @Override public void visit(Tree.SpreadArgument that) {
        super.visit(that);
        if (that.getExpression()!=null) {
            that.setTypeModel(that.getExpression().getTypeModel());
        }
    }

    @Override public void visit(Tree.ListedArgument that) {
        super.visit(that);
        if (that.getExpression()!=null) {
            that.setTypeModel(that.getExpression().getTypeModel());
        }
    }

    @Override public void visit(Tree.Annotation that) {
        super.visit(that);
        Declaration dec = ((Tree.MemberOrTypeExpression) that.getPrimary()).getDeclaration();
        if (dec!=null && !dec.isToplevel()) {
            that.getPrimary().addError("annotation must be a toplevel method reference");
        }
    }
    
    private boolean involvesUnknownTypes(Tree.ElementOrRange eor) {
        if (eor instanceof Tree.Element) {
            return ((Tree.Element) eor).getExpression().getTypeModel().isUnknown();
        }
        else {
            Tree.ElementRange er = (Tree.ElementRange) eor;
            return er.getLowerBound()!=null && er.getLowerBound().getTypeModel().isUnknown() ||
                    er.getUpperBound()!=null && er.getUpperBound().getTypeModel().isUnknown();
        }
    }
    
    @Override public void visit(Tree.IndexExpression that) {
        super.visit(that);
        ProducedType pt = type(that);
        if (pt==null) {
            that.addError("could not determine type of receiver");
        }
        else {
            /*if (that.getIndexOperator() instanceof Tree.SafeIndexOp) {
                if (unit.isOptionalType(pt)) {
                    pt = unit.getDefiniteType(pt);
                }
                else {
                    that.getPrimary().addError("receiving type not of optional type: " +
                            pt.getDeclaration().getName(unit) + " is not a subtype of Optional");
                }
            }*/
            if (that.getElementOrRange()==null) {
                that.addError("malformed index expression");
            }
            else if (!pt.isUnknown() && 
                     !involvesUnknownTypes(that.getElementOrRange())) {
                if (that.getElementOrRange() instanceof Tree.Element) {
                    ProducedType cst = pt.getSupertype(unit.getCorrespondenceDeclaration());
                    if (cst==null) {
                        that.getPrimary().addError("illegal receiving type for index expression: " +
                                pt.getDeclaration().getName(unit) + " is not a subtype of Correspondence");
                    }
                    else {
                        List<ProducedType> args = cst.getTypeArgumentList();
                        ProducedType kt = args.get(0);
                        ProducedType vt = args.get(1);
                        Tree.Element e = (Tree.Element) that.getElementOrRange();
                        checkAssignable(e.getExpression().getTypeModel(), kt, 
                                e.getExpression(), 
                                "index must be assignable to key type");
                        ProducedType rt = unit.getOptionalType(vt);
                        that.setTypeModel(rt);
                        Tree.Term t = e.getExpression().getTerm();
                        //TODO: in theory we could do a whole lot
                        //      more static-execution of the 
                        //      expression, but this seems
                        //      perfectly sufficient
                        refineTypeForTupleElement(that, pt, t);
                    }
                }
                else {
                    ProducedType rst = pt.getSupertype(unit.getRangedDeclaration());
                    if (rst==null) {
                        that.getPrimary().addError("illegal receiving type for index range expression: " +
                                pt.getDeclaration().getName(unit) + " is not a subtype of Ranged");
                    }
                    else {
                        List<ProducedType> args = rst.getTypeArgumentList();
                        ProducedType kt = args.get(0);
                        ProducedType rt = args.get(1);
                        Tree.ElementRange er = (Tree.ElementRange) that.getElementOrRange();
                        if (er.getLowerBound()!=null) {
                            checkAssignable(er.getLowerBound().getTypeModel(), kt,
                                    er.getLowerBound(), 
                                    "lower bound must be assignable to index type");
                        }
                        if (er.getUpperBound()!=null) {
                            checkAssignable(er.getUpperBound().getTypeModel(), kt,
                                    er.getUpperBound(), 
                                    "upper bound must be assignable to index type");
                        }
                        if (er.getLength()!=null) {
                            checkAssignable(er.getLength().getTypeModel(), 
                                    unit.getIntegerDeclaration().getType(),
                                    er.getLength(), 
                                    "length must be an integer");
                        }
                        that.setTypeModel(rt);
//                        if (er.getLowerBound()!=null && er.getUpperBound()!=null) {
//                            refineTypeForTupleRange(that, pt, 
//                                    er.getLowerBound().getTerm(), 
//                                    er.getUpperBound().getTerm());
//                        }
//                        else if (er.getLowerBound()!=null) {
                        if (er.getLowerBound()!=null && 
                                er.getUpperBound()==null &&
                                er.getLength()==null) {
                            refineTypeForTupleOpenRange(that, pt, 
                                    er.getLowerBound().getTerm());
                        }
                        /*if (that.getIndexOperator() instanceof Tree.SafeIndexOp) {
                            that.setTypeModel(unit.getOptionalType(that.getTypeModel()));
                        }*/
                    }
                }
            }
        }
    }

    private void refineTypeForTupleElement(Tree.IndexExpression that,
            ProducedType pt, Tree.Term t) {
        boolean negated = false;
        if (t instanceof Tree.NegativeOp) {
            t = ((Tree.NegativeOp) t).getTerm();
            negated = true;
        }
        else if (t instanceof Tree.PositiveOp) {
            t = ((Tree.PositiveOp) t).getTerm();
        }
        ProducedType tt = pt;
        if (tt.getSupertype(unit.getSequentialDeclaration())!=null) {
            if (t instanceof Tree.NaturalLiteral) {
                int index = Integer.parseInt(t.getText());
                if (negated) index = -index;
                List<ProducedType> elementTypes = getTupleElementTypes(tt);
                boolean variadic = isTupleLengthUnbounded(tt);
                int minimumLength = getTupleMinimumLength(tt);
                boolean atLeastOne = isTupleVariantAtLeastOne(tt);
                if (elementTypes!=null) {
                    if (elementTypes.isEmpty()) {
                        that.setTypeModel(unit.getNullDeclaration().getType());
                    }
                    else if (index<0) {
                        that.setTypeModel(unit.getNullDeclaration().getType());
                    }
                    else if (index<elementTypes.size()-(variadic?1:0)) {
                        ProducedType iet = elementTypes.get(index);
                        if (iet==null) return;
                        if (index>=minimumLength) {
                            iet = unionType(iet, 
                                    unit.getNullDeclaration().getType(), 
                                    unit);
                        }
                        that.setTypeModel(iet);
                    }
                    else if (variadic) {
                        ProducedType iet = elementTypes.get(elementTypes.size()-1);
                        if (iet==null) return;
                        ProducedType it = unit.getIteratedType(iet);
                        if (it==null) return;
                        if (!atLeastOne || index>=elementTypes.size()) {
                            it = unionType(it, 
                                    unit.getNullDeclaration().getType(), 
                                    unit);
                        }
                        that.setTypeModel(it);
                    }
                    else {
                        that.setTypeModel(unit.getNullDeclaration().getType());
                    }
                }
            }
        }
    }
    
    private void refineTypeForTupleOpenRange(Tree.IndexExpression that,
            ProducedType pt, Tree.Term l) {
        boolean lnegated = false;
        if (l instanceof Tree.NegativeOp) {
            l = ((Tree.NegativeOp) l).getTerm();
            lnegated = true;
        }
        else if (l instanceof Tree.PositiveOp) {
            l = ((Tree.PositiveOp) l).getTerm();
        }
        ProducedType tt = pt;
        if (tt.getSupertype(unit.getSequentialDeclaration())!=null) {
            if (l instanceof Tree.NaturalLiteral) {
                int lindex = Integer.parseInt(l.getText());
                if (lnegated) lindex = -lindex;
                List<ProducedType> elementTypes = getTupleElementTypes(tt);
                boolean variadic = isTupleLengthUnbounded(tt);
                boolean atLeastOne = isTupleVariantAtLeastOne(tt);
                int minimumLength = getTupleMinimumLength(tt);
                List<ProducedType> list = new ArrayList<ProducedType>();
                if (elementTypes!=null) {
                    if (lindex<0) {
                        lindex=0;
                    }
                    for (int index=lindex; 
                            index<elementTypes.size()-(variadic?1:0); 
                            index++) {
                        ProducedType et = elementTypes.get(index);
                        if (et==null) return;
                        list.add(et);
                    }
                    if (variadic) {
                        ProducedType it = elementTypes.get(elementTypes.size()-1);
                        if (it==null) return;
                        ProducedType rt = unit.getIteratedType(it);
                        if (rt==null) return;
                        list.add(rt);
                    }
                    ProducedType rt = unit.getTupleType(list, variadic, 
                            atLeastOne && lindex<elementTypes.size(), 
                            minimumLength-lindex);
                    //intersect with the type determined using
                    //Ranged, which may be narrower, for example,
                    //for String
                    that.setTypeModel(intersectionType(rt, 
                            that.getTypeModel(), unit));
                }
            }
        }
    }

    private ProducedType type(Tree.PostfixExpression that) {
        Tree.Primary p = that.getPrimary();
        return p==null ? null : p.getTypeModel();
    }
    
    @Override public void visit(Tree.PostfixOperatorExpression that) {
        super.visit(that);
        ProducedType type = type(that);
        visitIncrementDecrement(that, type, that.getTerm());
        checkAssignability(that.getTerm(), that);
    }

    @Override public void visit(Tree.PrefixOperatorExpression that) {
        super.visit(that);
        ProducedType type = type(that);
        visitIncrementDecrement(that, type, that.getTerm());
        checkAssignability(that.getTerm(), that);
    }
    
    private void visitIncrementDecrement(Tree.Term that,
            ProducedType pt, Tree.Term term) {
        if (pt!=null && !pt.isUnknown()) {
            ProducedType ot = checkSupertype(pt, unit.getOrdinalDeclaration(), 
                    term, "operand expression must be of ordinal type");
            if (ot!=null) {
                ProducedType ta = ot.getTypeArgumentList().get(0);
                checkAssignable(ta, pt, that, 
                        "result type must be assignable to declared type");
            }
            that.setTypeModel(pt);
        }
    }
    
    /*@Override public void visit(Tree.SumOp that) {
        super.visit( (Tree.BinaryOperatorExpression) that );
        ProducedType lhst = leftType(that);
        if (lhst!=null) {
            //take into account overloading of + operator
            if (lhst.isSubtypeOf(getStringDeclaration().getType())) {
                visitBinaryOperator(that, getStringDeclaration());
            }
            else {
                visitBinaryOperator(that, getNumericDeclaration());
            }
        }
    }*/

    private void visitComparisonOperator(Tree.BinaryOperatorExpression that, 
            TypeDeclaration type) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkOperandTypes(lhst, rhst, type, that, 
                    "operand expressions must be comparable");
        }
        that.setTypeModel( unit.getBooleanDeclaration().getType() );            
    }
    
    private void visitCompareOperator(Tree.CompareOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkOperandTypes(lhst, rhst, unit.getComparableDeclaration(), that, 
                    "operand expressions must be comparable");
        }
        that.setTypeModel( unit.getComparisonDeclaration().getType() );            
    }
    
    private void visitRangeOperator(Tree.RangeOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            ProducedType ot = checkOperandTypes(lhst, rhst, 
                    unit.getOrdinalDeclaration(), that,
                    "operand expressions must be of compatible ordinal type");
            ProducedType ct = checkOperandTypes(lhst, rhst, 
                    unit.getComparableDeclaration(), that, 
                    "operand expressions must be comparable");
            if (ot!=null) {
                if (ct!=null) {
                    checkAssignable(ot, ct, that, "ordinal type must be assignable to comparable type");
                }
                ProducedType pt = producedType(unit.getRangeDeclaration(), ot);
                that.setTypeModel(pt);
            }
        }
    }
    
    private void visitSegmentOperator(Tree.SegmentOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            ProducedType ot = checkSupertype(lhst, unit.getOrdinalDeclaration(), 
                    that.getLeftTerm(), "left operand must be of ordinal type");
            checkAssignable(rhst, unit.getIntegerDeclaration().getType(), 
                    that.getRightTerm(), "right operand must be an integer");
            if (ot!=null) {
                ProducedType ta = ot.getTypeArgumentList().get(0);
                that.setTypeModel(unit.getSequentialType(ta));
            }
        }
    }
    
    private void visitEntryOperator(Tree.EntryOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkSupertype(lhst, unit.getObjectDeclaration(), that.getLeftTerm(), 
                    "operand expression must not be an optional type");
            checkSupertype(rhst, unit.getObjectDeclaration(), that.getRightTerm(), 
                    "operand expression must not be an optional type");
            that.setTypeModel( unit.getEntryType(lhst, rhst) );
        }
    }
    
    private void visitEqualityOperator(Tree.BinaryOperatorExpression that, 
            TypeDeclaration td) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkSupertype(lhst, td, that.getLeftTerm(), 
                    "operand expression must be of type " + td.getName());
            checkSupertype(rhst, td, that.getRightTerm(), 
                    "operand expression must not be of type" + td.getName());
        }
        that.setTypeModel(unit.getBooleanDeclaration().getType());
    }
    
    private void visitAssignOperator(Tree.AssignOp that) {
        ProducedType rhst = rightType(that);
        ProducedType lhst = leftType(that);
        if ( rhst!=null && lhst!=null  && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            ProducedType leftHandType = lhst;
            // allow assigning null to java properties that could after all be null
            if (hasUncheckedNulls(that.getLeftTerm()))
                leftHandType = unit.getOptionalType(leftHandType);
            checkAssignable(rhst, leftHandType, that.getRightTerm(), 
                    "assigned expression must be assignable to declared type", 
                    2100);
        }
        that.setTypeModel(rhst);
//      that.setTypeModel(lhst); //this version is easier on backend
    }

    private ProducedType checkOperandTypes(ProducedType lhst, ProducedType rhst, 
            TypeDeclaration td, Node node, String message) {
        ProducedType lhsst = checkSupertype(lhst, td, node, message);
        if (lhsst!=null) {
            ProducedType t = lhsst.getTypeArgumentList().get(0);
            checkAssignable(rhst, t, node, message);
            return t;
        }
        else {
            return null;
        }
    }
    
    private void visitArithmeticOperator(Tree.BinaryOperatorExpression that, 
            TypeDeclaration type) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            //hardcoded implicit type conversion Integer->Float
            TypeDeclaration fd = unit.getFloatDeclaration();
            TypeDeclaration id = unit.getIntegerDeclaration();
            if (rhst.getSupertype(fd)!=null &&
                    lhst.getSupertype(id)!=null) {
                lhst = fd.getType();
            }
            else if (rhst.getSupertype(id)!=null &&
                    lhst.getSupertype(fd)!=null) {
                rhst = fd.getType();
            }
            ProducedType nt = checkSupertype(lhst, type, that.getLeftTerm(), 
                    "operand expression must be of numeric type");
            if (nt!=null) {
                List<ProducedType> tal = nt.getTypeArgumentList();
                if (tal.isEmpty()) return;
                ProducedType tt = tal.get(0);
                that.setTypeModel(tt);
                ProducedType ot;
                if (that instanceof Tree.PowerOp) {
                    if (tal.size()<2) return;
                    ot = tal.get(1);
                }
                else {
                    ot = tt;
                }
                checkAssignable(rhst, ot, that, 
                        "operands must be of compatible numeric type");
            }
        }
    }
    
    private void visitArithmeticAssignOperator(Tree.BinaryOperatorExpression that, 
            TypeDeclaration type) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            //hardcoded implicit type conversion Integer->Float
            TypeDeclaration fd = unit.getFloatDeclaration();
            TypeDeclaration id = unit.getIntegerDeclaration();
            if (rhst.getSupertype(id)!=null &&
                    lhst.getSupertype(fd)!=null) {
                rhst = fd.getType();
            }
            ProducedType nt = checkSupertype(lhst, type, that.getLeftTerm(), 
                    "operand expression must be of numeric type");
            that.setTypeModel(lhst);
            if (nt!=null) {
                ProducedType t = nt.getTypeArgumentList().get(0);
                //that.setTypeModel(t); //stef requests lhst to make it easier on backend
                checkAssignable(rhst, t, that, 
                        "operands must be of compatible numeric type");
                checkAssignable(t, lhst, that, 
                        "result type must be assignable to declared type");
            }
        }
    }
    
    private void visitSetOperator(Tree.BitwiseOp that) {
        //TypeDeclaration sd = unit.getSetDeclaration();
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkAssignable(lhst, unit.getSetType(unit.getObjectDeclaration().getType()), 
                    that.getLeftTerm(), "set operand expression must be a set");
            checkAssignable(lhst, unit.getSetType(unit.getObjectDeclaration().getType()), 
                    that.getRightTerm(), "set operand expression must be a set");
            ProducedType lhset = unit.getSetElementType(lhst);
            ProducedType rhset = unit.getSetElementType(rhst);
            ProducedType et;
            if (that instanceof Tree.IntersectionOp) {
                et = intersectionType(rhset, lhset, unit);
            }
            else if (that instanceof Tree.ComplementOp) {
                et = lhset;
            }
            else {
                et = unionType(rhset, lhset, unit);
            }            
            that.setTypeModel(unit.getSetType(et));
        }
    }

    private void visitSetAssignmentOperator(Tree.BitwiseAssignmentOp that) {
        //TypeDeclaration sd = unit.getSetDeclaration();
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkAssignable(lhst, unit.getSetType(unit.getObjectDeclaration().getType()), 
                    that.getLeftTerm(), "set operand expression must be a set");
            checkAssignable(lhst, unit.getSetType(unit.getObjectDeclaration().getType()), 
                    that.getRightTerm(), "set operand expression must be a set");
            ProducedType lhset = unit.getSetElementType(lhst);
            ProducedType rhset = unit.getSetElementType(rhst);
            if (that instanceof Tree.UnionAssignOp ||
                    that instanceof Tree.XorAssignOp) {
                checkAssignable(rhset, lhset, that.getRightTerm(), 
                        "resulting set element type must be assignable to to declared set element type");
            }            
            that.setTypeModel(unit.getSetType(lhset)); //in theory, we could make this narrower
        }
    }

    private void visitLogicalOperator(Tree.BinaryOperatorExpression that) {
        ProducedType bt = unit.getBooleanDeclaration().getType();
        ProducedType lt = leftType(that);
        ProducedType rt = rightType(that);
        if (lt!=null && rt!=null && 
                !rt.isUnknown() && !lt.isUnknown()) {
            checkAssignable(lt, bt, that, 
                    "logical operand expression must be a boolean value");
            checkAssignable(rt, bt, that, 
                    "logical operand expression must be a boolean value");
        }
        that.setTypeModel(bt);
    }

    private void visitDefaultOperator(Tree.DefaultOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkOptional(lhst, that.getLeftTerm(), that.getLeftTerm());
            List<ProducedType> list = new ArrayList<ProducedType>();
            addToUnion(list, unit.denotableType(rhst));
            addToUnion(list, unit.getDefiniteType(unit.denotableType(lhst)));
            UnionType ut = new UnionType(unit);
            ut.setCaseTypes(list);
            ProducedType rt = ut.getType();
            that.setTypeModel(rt);
            /*that.setTypeModel(rhst);
            ProducedType ot;
            if (isOptionalType(rhst)) {
                ot = rhst;
            }
            else {
                ot = getOptionalType(rhst);
            }
            if (!lhst.isSubtypeOf(ot)) {
                that.getLeftTerm().addError("must be of type: " + 
                        ot.getProducedTypeName(unit));
            }*/
        }
    }

    private void visitThenOperator(Tree.ThenOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( lhst!=null && !lhst.isUnknown()) {
            checkAssignable(lhst, unit.getBooleanDeclaration().getType(), that.getLeftTerm(), 
                    "operand expression must be a boolean value");
        }
        if ( rhst!=null && !rhst.isUnknown()) {
            checkAssignable(rhst, unit.getObjectDeclaration().getType(), that.getRightTerm(),
                    "operand expression may not be an optional type");
            that.setTypeModel(unit.getOptionalType(unit.denotableType(rhst)));
        }
    }

    private void visitInOperator(Tree.InOp that) {
        ProducedType lhst = leftType(that);
        ProducedType rhst = rightType(that);
        if ( rhst!=null && lhst!=null && 
                !rhst.isUnknown() && !lhst.isUnknown()) {
            checkAssignable(lhst, unit.getObjectDeclaration().getType(), that.getLeftTerm(), 
                    "operand expression must support equality");
            checkAssignable(rhst, unit.getCategoryDeclaration().getType(), that.getRightTerm(), 
                    "operand expression must be a category");
        }
        that.setTypeModel( unit.getBooleanDeclaration().getType() );
    }
    
    private void visitUnaryOperator(Tree.UnaryOperatorExpression that, 
            TypeDeclaration type) {
        ProducedType t = type(that);
        if (t!=null && !t.isUnknown()) {
            ProducedType nt = checkSupertype(t, type, that.getTerm(), 
                    "operand expression must be of correct type");
            if (nt!=null) {
                ProducedType at = nt.getTypeArguments().isEmpty() ? 
                        nt : nt.getTypeArgumentList().get(0);
                that.setTypeModel(at);
            }
        }
    }

    private void visitExistsOperator(Tree.Exists that) {
        checkOptional(type(that), that.getTerm(), that);
        that.setTypeModel(unit.getBooleanDeclaration().getType());
    }
    
    private void visitNonemptyOperator(Tree.Nonempty that) {
        checkEmpty(type(that), that.getTerm(), that);
        that.setTypeModel(unit.getBooleanDeclaration().getType());
    }
    
    private void visitOfOperator(Tree.OfOp that) {
        Tree.Type rt = that.getType();
        if (rt!=null) {
            ProducedType t = rt.getTypeModel();
            if (t!=null) {
                that.setTypeModel(t);
                if (that.getTerm()!=null) {
                    if (that.getTerm()!=null) {
                        ProducedType pt = that.getTerm().getTypeModel();
                        if (pt!=null) {
                            if (!t.covers(pt)) {
                                that.addError("specified type does not cover the cases of the operand expression: " +
                                        t.getProducedTypeName(unit) + " does not cover " + pt.getProducedTypeName(unit));
                            }
                        }
                    }
                }
            }
        }
    }
    
    private void visitIsOperator(Tree.IsOp that) {
        Tree.Type rt = that.getType();
        if (rt!=null) {
            ProducedType t = rt.getTypeModel();
            if (t!=null) {
                checkReified(that, t);
                if (that.getTerm()!=null) {
                    ProducedType pt = that.getTerm().getTypeModel();
                    if (pt!=null && pt.isSubtypeOf(t)) {
                        that.addError("expression type is a subtype of the type: " +
                                pt.getProducedTypeName(unit) + " is assignable to " +
                                t.getProducedTypeName(unit));
                    }
                    else {
                        ProducedType it = intersectionType(t, pt, unit);
                        if (it.getDeclaration() instanceof NothingType) {
                            that.addError("tests assignability to Nothing type: intersection of " +
                                    pt.getProducedTypeName(unit) + " and " + 
                                    t.getProducedTypeName(unit) +
                                    " is empty");
                        }
                    }
                }
            }
        }
        that.setTypeModel(unit.getBooleanDeclaration().getType());
    }
    
    private void checkAssignability(Tree.Term that, Node node) {
        if (that instanceof Tree.BaseMemberExpression ||
                that instanceof Tree.QualifiedMemberExpression) {
            ProducedReference pr = ((Tree.MemberOrTypeExpression) that).getTarget();
            if (pr!=null) {
                Declaration dec = pr.getDeclaration();
                if (!(dec instanceof Value | dec instanceof Getter)) {
                    that.addError("member cannot be assigned: " 
                            + dec.getName(unit));
                }
                else if (!((MethodOrValue) dec).isVariable() &&
                         !((MethodOrValue) dec).isLate()) {
                    that.addError("value is not variable: " 
                            + dec.getName(unit), 800);
                }
            }
        }
        else {
            that.addError("expression cannot be assigned");
        }
    }
    
    private ProducedType rightType(Tree.BinaryOperatorExpression that) {
        Tree.Term rt = that.getRightTerm();
        return rt==null? null : rt.getTypeModel();
    }

    private ProducedType leftType(Tree.BinaryOperatorExpression that) {
        Tree.Term lt = that.getLeftTerm();
        return lt==null ? null : lt.getTypeModel();
    }
    
    private ProducedType type(Tree.UnaryOperatorExpression that) {
        Tree.Term t = that.getTerm();
        return t==null ? null : t.getTypeModel();
    }
    
    private Interface getArithmeticDeclaration(Tree.ArithmeticOp that) {
        if (that instanceof Tree.PowerOp) {
            return unit.getExponentiableDeclaration();
        }
        if (that instanceof Tree.SumOp) {
            return unit.getSummableDeclaration();
        }
        else if (that instanceof Tree.RemainderOp) {
            return unit.getIntegralDeclaration();
        }
        else {
            return unit.getNumericDeclaration();
        }
    }

    private Interface getArithmeticDeclaration(Tree.ArithmeticAssignmentOp that) {
        if (that instanceof Tree.AddAssignOp) {
            return unit.getSummableDeclaration();
        }
        else if (that instanceof Tree.RemainderAssignOp) {
            return unit.getIntegralDeclaration();
        }
        else {
            return unit.getNumericDeclaration();
        }
    }

    @Override public void visit(Tree.ArithmeticOp that) {
        super.visit(that);
        visitArithmeticOperator(that, getArithmeticDeclaration(that));
    }

    @Override public void visit(Tree.BitwiseOp that) {
        super.visit(that);
        visitSetOperator(that);
    }

    @Override public void visit(Tree.LogicalOp that) {
        super.visit(that);
        visitLogicalOperator(that);
    }

    @Override public void visit(Tree.EqualityOp that) {
        super.visit(that);
        visitEqualityOperator(that, unit.getObjectDeclaration());
    }

    @Override public void visit(Tree.ComparisonOp that) {
        super.visit(that);
        visitComparisonOperator(that, unit.getComparableDeclaration());
    }

    @Override public void visit(Tree.IdenticalOp that) {
        super.visit(that);
        visitEqualityOperator(that, unit.getIdentifiableDeclaration());
    }

    @Override public void visit(Tree.CompareOp that) {
        super.visit(that);
        visitCompareOperator(that);
    }

    @Override public void visit(Tree.DefaultOp that) {
        super.visit(that);
        visitDefaultOperator(that);
    }
        
    @Override public void visit(Tree.ThenOp that) {
        super.visit(that);
        visitThenOperator(that);
    }
        
    @Override public void visit(Tree.NegativeOp that) {
        super.visit(that);
        visitUnaryOperator(that, unit.getInvertableDeclaration());
    }
        
    @Override public void visit(Tree.PositiveOp that) {
        super.visit(that);
        visitUnaryOperator(that, unit.getInvertableDeclaration());
    }
    
    @Override public void visit(Tree.NotOp that) {
        super.visit(that);
        visitUnaryOperator(that, unit.getBooleanDeclaration());
    }
    
    @Override public void visit(Tree.AssignOp that) {
        super.visit(that);
        visitAssignOperator(that);
        checkAssignability(that.getLeftTerm(), that);
    }
    
    @Override public void visit(Tree.ArithmeticAssignmentOp that) {
        super.visit(that);
        visitArithmeticAssignOperator(that, getArithmeticDeclaration(that));
        checkAssignability(that.getLeftTerm(), that);
    }
    
    @Override public void visit(Tree.LogicalAssignmentOp that) {
        super.visit(that);
        visitLogicalOperator(that);
        checkAssignability(that.getLeftTerm(), that);
    }
    
    @Override public void visit(Tree.BitwiseAssignmentOp that) {
        super.visit(that);
        visitSetAssignmentOperator(that);
        checkAssignability(that.getLeftTerm(), that);
    }
    
    @Override public void visit(Tree.RangeOp that) {
        super.visit(that);
        visitRangeOperator(that);
    }
    
    @Override public void visit(Tree.SegmentOp that) {
        super.visit(that);
        visitSegmentOperator(that);
    }
        
    @Override public void visit(Tree.EntryOp that) {
        super.visit(that);
        visitEntryOperator(that);
    }
    
    @Override public void visit(Tree.Exists that) {
        super.visit(that);
        visitExistsOperator(that);
    }
    
    @Override public void visit(Tree.Nonempty that) {
        super.visit(that);
        visitNonemptyOperator(that);
    }
    
    @Override public void visit(Tree.IsOp that) {
        super.visit(that);
        visitIsOperator(that);
    }
    
    @Override public void visit(Tree.OfOp that) {
        super.visit(that);
        visitOfOperator(that);
    }
    
    @Override public void visit(Tree.Extends that) {
        super.visit(that);
        that.addWarning("extends operator not yet supported");
    }
    
    @Override public void visit(Tree.Satisfies that) {
        super.visit(that);
        that.addWarning("satisfies operator not yet supported");
    }
    
    @Override public void visit(Tree.InOp that) {
        super.visit(that);
        visitInOperator(that);
    }
    
    //Atoms:
    
    private void checkOverloadedReference(Tree.MemberOrTypeExpression that) {
        if (isAbstraction(that.getDeclaration()) &&
                that.getSignature() != null) {
            that.addError("ambiguous reference to overloaded method or class: " +
                    that.getDeclaration().getName(unit));
        }
    }
    
    private static Declaration getSupertypeDeclaration(Tree.BaseMemberOrTypeExpression that, 
            Tree.SupertypeQualifier sq) {
        String typeName = name(sq.getIdentifier());
        Declaration dec = that.getScope().getMemberOrParameter(that.getUnit(), typeName, null, false);
        if (dec instanceof TypeDeclaration) {
            ClassOrInterface ci = getContainingClassOrInterface(that.getScope());
            if (ci.getType().getSupertype((TypeDeclaration) dec)==null) {
                sq.addError("not a supertype of containing class or interface: " +
                        ci.getName() + " does not inherit " + dec.getName());
            }
            Declaration member = dec.getMember(name(that.getIdentifier()), that.getSignature(), that.getEllipsis());
            if (member!=null && member.isFormal()) {
                that.addError("supertype member is declared formal: " + 
                        typeName + "::" + member.getName());
            }
            Declaration etm = ci.getExtendedTypeDeclaration()
                    .getMember(member.getName(), that.getSignature(), that.getEllipsis());
            if (etm!=null && !etm.equals(member) && etm.refines(member)) {
                that.addError("inherited member is refined by intervening superclass: " + 
                        ((TypeDeclaration) etm.getContainer()).getName() + 
                        " refines " + member.getName() + " declared by " + 
                        ((TypeDeclaration) member.getContainer()).getName());
            }
            for (TypeDeclaration td: ci.getSatisfiedTypeDeclarations()) {
                Declaration stm = td.getMember(member.getName(), that.getSignature(), that.getEllipsis());
                if (stm!=null && !stm.equals(member) && stm.refines(member)) {
                    that.addError("inherited member is refined by intervening superinterface: " + 
                            ((TypeDeclaration) stm.getContainer()).getName() + 
                            " refines " + member.getName() + " declared by " + 
                            ((TypeDeclaration) member.getContainer()).getName());
                }
            }
            return member;
        }
        else {
            sq.addError("qualifying supertype does not exist: " + typeName);
            return null;
        }
    }

    @Override public void visit(Tree.BaseMemberExpression that) {
        /*if (that.getTypeArgumentList()!=null)
            that.getTypeArgumentList().visit(this);*/
        super.visit(that);
        TypedDeclaration member;
        Tree.SupertypeQualifier sq = that.getSupertypeQualifier();
        if (sq==null) {
            member = getBaseDeclaration(that, that.getSignature(), that.getEllipsis());
        }
        else {
            member = getSupertypeMemberDeclaration(that, sq);
        }
        if (member==null) {
            if (!dynamic) {
                that.addError("method or attribute does not exist: " +
                        name(that.getIdentifier()), 100);
                unit.getUnresolvedReferences().add(that.getIdentifier());
            }
        }
        else {
            that.setDeclaration(member);
            if (!member.isVisible(that.getScope())) {
                that.addError("method or attribute is not visible: " +
                        name(that.getIdentifier()), 400);
            }
            Tree.TypeArguments tal = that.getTypeArguments();
            if (explicitTypeArguments(member, tal, that)) {
                List<ProducedType> ta = getTypeArguments(tal, 
                        getTypeParameters(member));
                tal.setTypeModels(ta);
                visitBaseMemberExpression(that, member, ta, tal);
                //otherwise infer type arguments later
            }
            else {
                //TODO: set the correct metatype
                that.setTypeModel(unit.getAnythingDeclaration().getType());
            }
            /*if (defaultArgument) {
                if (member.isClassOrInterfaceMember()) {
                    that.addWarning("references to this from default argument expressions not yet supported");
                }
            }*/
            checkOverloadedReference(that);
        }
    }
    
    private static TypedDeclaration getSupertypeMemberDeclaration(
            Tree.BaseMemberExpression that, Tree.SupertypeQualifier sq) {
        TypedDeclaration member;
        Declaration dec = getSupertypeDeclaration(that, sq);
        if (dec instanceof TypedDeclaration) {
            member = (TypedDeclaration) dec;
        }
        else {
            member = null;
        }
        return member;
    }
    
    private List<TypeParameter> getTypeParameters(Declaration member) {
        return member instanceof Generic ? 
                ((Generic) member).getTypeParameters() : 
                Collections.<TypeParameter>emptyList();
    }
    
    @Override public void visit(Tree.QualifiedMemberExpression that) {
        /*that.getPrimary().visit(this);
        if (that.getTypeArgumentList()!=null)
            that.getTypeArgumentList().visit(this);*/
        super.visit(that);
        ProducedType pt = that.getPrimary().getTypeModel();
        boolean packageQualified = that.getPrimary() instanceof Tree.Package;
        boolean check = packageQualified || 
                pt!=null && !pt.getType().isUnknown();
        if (check && that.getIdentifier()!=null && 
                !that.getIdentifier().getText().equals("")) {
            TypedDeclaration member;
            String name = name(that.getIdentifier());
            String container;
            boolean ambiguous;
            if (packageQualified) {
                container = "package " + unit.getPackage().getNameAsString();
                member = (TypedDeclaration) unit.getPackage()
                        .getMember(name, that.getSignature(), that.getEllipsis());
                ambiguous = false;
            }
            else {
                pt = pt.resolveAliases(); //needed for aliases like "alias Id<T> => T"
                TypeDeclaration d = unwrap(pt, that).getDeclaration();
                container = "type " + d.getName(unit);
                member = (TypedDeclaration) d.getMember(name, unit, 
                        that.getSignature(), that.getEllipsis());
                ambiguous = member==null && d.isMemberAmbiguous(name, unit, 
                        that.getSignature(), that.getEllipsis());
            }
            if (member==null) {
                if (ambiguous) {
                    that.addError("member method or attribute is ambiguous: " +
                            name + " for " + container);
                }
                else {
                    that.addError("member method or attribute does not exist: " +
                            name + " in " + container, 100);
                    unit.getUnresolvedReferences().add(that.getIdentifier());
                }
            }
            else {
                that.setDeclaration(member);
                if (!member.isVisible(that.getScope())) {
                    that.addError("member method or attribute is not visible: " +
                            name + " of " + container, 400);
                }
                boolean selfReference = that.getPrimary() instanceof Tree.This ||
                        that.getPrimary() instanceof Tree.Super;
                if (member.isProtectedVisibility() && !selfReference) {
                    that.addError("member method or attribute is not visible: " +
                            name + " of " + container);
                }
                if (!selfReference && !member.isShared()) {
                    member.setOtherInstanceAccess(true);
                }
                Tree.TypeArguments tal = that.getTypeArguments();
                if (explicitTypeArguments(member,tal, that)) {
                    List<ProducedType> ta = getTypeArguments(tal,
                            getTypeParameters(member));
                    tal.setTypeModels(ta);
                    if (packageQualified) {
                        visitBaseMemberExpression(that, member, ta, tal);
                    }
                    else {
                        visitQualifiedMemberExpression(that, pt, member, ta, tal);
                    }
                    //otherwise infer type arguments later
                }
                else {
                    //TODO: set the correct metatype
                    that.setTypeModel(unit.getAnythingDeclaration().getType());
                }
                checkOverloadedReference(that);
            }
            if (that.getPrimary() instanceof Tree.Super) {
                if (member!=null && member.isFormal()) {
                    that.addError("superclass member is formal: " + 
                            member.getName() + " declared by " + 
                            ((Declaration) member.getContainer()).getName());
                }
            }
        }
    }

    private void visitQualifiedMemberExpression(Tree.QualifiedMemberExpression that,
            ProducedType receivingType, TypedDeclaration member, 
            List<ProducedType> typeArgs, Tree.TypeArguments tal) {
        ProducedType receiverType = unwrap(receivingType, that);
        if (acceptsTypeArguments(receiverType, member, typeArgs, tal, that)) {
            ProducedTypedReference ptr = receiverType.getTypedMember(member, typeArgs);
            /*if (ptr==null) {
                that.addError("member method or attribute does not exist: " + 
                        member.getName(unit) + " of type " + 
                        receiverType.getDeclaration().getName(unit));
            }
            else {*/
                ProducedType t = ptr.getFullType(wrap(ptr.getType(), receivingType, that));
                that.setTarget(ptr); //TODO: how do we wrap ptr???
                if (isTypeUnknown(t)) {
                    if (!dynamic) {
                        that.addError("could not determine type of method or attribute reference: " +
                                member.getName(unit)  + " of " + receiverType.getDeclaration().getName(unit));
                    }
                }
                else {
                    that.setTypeModel(t);
                }
            //}
        }
    }
    
    private void visitBaseMemberExpression(Tree.StaticMemberOrTypeExpression that, 
            TypedDeclaration member, List<ProducedType> typeArgs, Tree.TypeArguments tal) {
        if (acceptsTypeArguments(member, typeArgs, tal, that)) {
            ProducedType outerType = that.getScope().getDeclaringType(member);
            ProducedTypedReference pr = member.getProducedTypedReference(outerType, typeArgs);
            that.setTarget(pr);
            ProducedType t = pr.getFullType();
            if (isTypeUnknown(t)) {
                if (!dynamic) {
                    that.addError("could not determine type of method or attribute reference: " +
                            member.getName(unit));
                }
            }
            else {
                that.setTypeModel(t);
            }
        }
    }

    @Override public void visit(Tree.BaseTypeExpression that) {
        super.visit(that);
        /*if (that.getTypeArgumentList()!=null)
            that.getTypeArgumentList().visit(this);*/
        Tree.SupertypeQualifier sq = that.getSupertypeQualifier();
        TypeDeclaration type;
        if (sq==null) {
            type = getBaseDeclaration(that, that.getSignature(), that.getEllipsis());
        }
        else {
            type = getSupertypeTypeDeclaration(that, sq);
        }
        if (type==null) {
            that.addError("type does not exist: " + 
                    name(that.getIdentifier()), 100);
            unit.getUnresolvedReferences().add(that.getIdentifier());
        }
        else {
            that.setDeclaration(type);
            if (!type.isVisible(that.getScope())) {
                that.addError("type is not visible: " +
                        name(that.getIdentifier()), 400);
            }
            Tree.TypeArguments tal = that.getTypeArguments();
            if (explicitTypeArguments(type, tal, that)) {
                List<ProducedType> ta = getTypeArguments(tal, 
                        type.getTypeParameters());
                tal.setTypeModels(ta);
                visitBaseTypeExpression(that, type, ta, tal);
                //otherwise infer type arguments later
            }
            else {
                //TODO: set the correct metatype
                that.setTypeModel(unit.getAnythingDeclaration().getType());
            }
            checkOverloadedReference(that);
        }
    }

    static TypeDeclaration getSupertypeTypeDeclaration(Tree.BaseTypeExpression that, 
            Tree.SupertypeQualifier sq) {
        Declaration dec = getSupertypeDeclaration(that, sq);
        if (dec instanceof TypeDeclaration) {
            return (TypeDeclaration) dec;
        }
        else {
            return null;
        }
    }
        
    @Override public void visit(Tree.ExtendedTypeExpression that) {
        super.visit(that);
        Declaration dec = that.getDeclaration();
        if (dec instanceof Class) {
            Class c = (Class) dec;
            if (c.isAbstraction()) { 
                //if the constructor is overloaded
                //resolve the right overloaded version
                Declaration result = findMatchingOverloadedClass(c, that.getSignature(), that.getEllipsis());
                if (result!=null && result!=dec) {
                    //patch the reference, which was already
                    //initialized to the abstraction
                    that.setDeclaration((TypeDeclaration) result);
                    checkOverloadedReference(that);
                }
                //else report to user that we could not
                //find a matching overloaded constructor
            }
        }
    }
        
    @Override public void visit(Tree.QualifiedTypeExpression that) {
        super.visit(that);
        /*that.getPrimary().visit(this);
        if (that.getTypeArgumentList()!=null)
            that.getTypeArgumentList().visit(this);*/
        ProducedType pt = that.getPrimary().getTypeModel();
        boolean packageQualified = that.getPrimary() instanceof Tree.Package;
        if (pt!=null||packageQualified) {
            TypeDeclaration type;
            String name = name(that.getIdentifier());
            String container;
            boolean ambiguous;
            if (packageQualified) {
                container = "package " + unit.getPackage().getNameAsString();
                type = (TypeDeclaration) unit.getPackage()
                        .getMember(name, that.getSignature(), that.getEllipsis());
                ambiguous = false;
            }
            else {
                pt = pt.resolveAliases(); //needed for aliases like "alias Id<T> => T"
                TypeDeclaration d = unwrap(pt, that).getDeclaration();
                container = "type " + d.getName(unit);
                type = (TypeDeclaration) d.getMember(name, unit, 
                        that.getSignature(), that.getEllipsis());
                ambiguous = type==null && d.isMemberAmbiguous(name, unit, 
                        that.getSignature(), that.getEllipsis());
            }
            if (type==null) {
                if (ambiguous) {
                    that.addError("member type is ambiguous: " +
                            name + " for " + container);
                }
                else {
                    that.addError("member type does not exist: " +
                            name + " in " + container, 100);
                    unit.getUnresolvedReferences().add(that.getIdentifier());
                }
            }
            else {
                that.setDeclaration(type);
                if (!type.isVisible(that.getScope())) {
                    that.addError("member type is not visible: " +
                            name + " of " + container, 400);
                }
                boolean selfReference = that.getPrimary() instanceof Tree.This &&
                                        that.getPrimary() instanceof Tree.Super;
                if (type.isProtectedVisibility() && !selfReference) {
                    that.addError("member type is not visible: " +
                            name + " of " + container);
                }
                if (!selfReference && !type.isShared()) {
                    type.setOtherInstanceAccess(true);
                }
                Tree.TypeArguments tal = that.getTypeArguments();
                if (explicitTypeArguments(type, tal, that)) {
                    List<ProducedType> ta = getTypeArguments(tal,
                            type.getTypeParameters());
                    tal.setTypeModels(ta);
                    if (packageQualified) {
                        visitBaseTypeExpression(that, type, ta, tal);
                    }
                    else {
                        visitQualifiedTypeExpression(that, pt, type, ta, tal);
                    }
                    //otherwise infer type arguments later
                }
                else {
                    //TODO: set the correct metatype
                    that.setTypeModel(unit.getAnythingDeclaration().getType());
                }
                checkOverloadedReference(that);
            }
            //TODO: this is temporary until we get metamodel reference expressions!
            if (that.getPrimary() instanceof Tree.BaseTypeExpression ||
                    that.getPrimary() instanceof Tree.QualifiedTypeExpression) {
                ProducedReference target = that.getTarget();
                if (target!=null) {
                    checkTypeBelongsToContainingScope(target.getType(), 
                            that.getScope(), that);
                }
            }
            if (!inExtendsClause && that.getPrimary() instanceof Tree.Super) {
                if (type!=null && type.isFormal()) {
                    that.addError("superclass member class is formal");
                }
            }
        }
    }

    private boolean explicitTypeArguments(Declaration dec, Tree.TypeArguments tal, 
            Tree.MemberOrTypeExpression that) {
        return !dec.isParameterized() || 
                tal instanceof Tree.TypeArgumentList;
                //TODO: enable this line to enable
                //      typechecking of references
                //      without type arguments
                //|| !that.getDirectlyInvoked();
    }
    
    @Override public void visit(Tree.SimpleType that) {
        //this one is a declaration, not an expression!
        //we are only validating type arguments here
        super.visit(that);
        ProducedType pt = that.getTypeModel();
        if (pt!=null) {
            TypeDeclaration type = that.getDeclarationModel();//pt.getDeclaration()
            Tree.TypeArgumentList tal = that.getTypeArgumentList();
            //No type inference for declarations
            if (type!=null) {
                List<ProducedType> ta = getTypeArguments(tal, 
                        type.getTypeParameters());
                acceptsTypeArguments(type, ta, tal, that);
                //the type has already been set by TypeVisitor
            }
        }
    }
    
    @Override public void visit(Tree.EntryType that) {
        super.visit(that);
        checkAssignable(that.getKeyType().getTypeModel(), unit.getObjectDeclaration().getType(), 
                that.getKeyType(), "entry key type must not be an optional type");
        checkAssignable(that.getValueType().getTypeModel(), unit.getObjectDeclaration().getType(), 
                that.getValueType(), "entry item type must not be an optional type");
    }

    private void visitQualifiedTypeExpression(Tree.QualifiedTypeExpression that,
            ProducedType receivingType, TypeDeclaration type, 
            List<ProducedType> typeArgs, Tree.TypeArguments tal) {
        ProducedType receiverType = unwrap(receivingType, that);
        if (acceptsTypeArguments(receiverType, type, typeArgs, tal, that)) {
            ProducedType t = receiverType.getTypeMember(type, typeArgs);
            ProducedType ft = isAbstractType(t) ?
                    unit.getAnythingDeclaration().getType() : //TODO: set the correct metatype
                    t.getFullType(wrap(t, receivingType, that));
            that.setTypeModel(ft);
            that.setTarget(t);
        }
    }

    private void visitBaseTypeExpression(Tree.StaticMemberOrTypeExpression that, 
            TypeDeclaration type, List<ProducedType> typeArgs, Tree.TypeArguments tal) {
        ProducedType outerType = that.getScope().getDeclaringType(type);
        ProducedType t = type.getProducedType(outerType, typeArgs);
//        if (!type.isAlias()) {
            //TODO: remove this awful hack which means
            //      we can't define aliases for types
            //      with sequenced type parameters
            type = t.getDeclaration();
//        }
        if ( acceptsTypeArguments(type, typeArgs, tal, that) ) {
            ProducedType ft = isAbstractType(t) ?
                    unit.getAnythingDeclaration().getType() : //TODO: set the correct metatype
                    t.getFullType();
            that.setTypeModel(ft);
            that.setTarget(t);
        }
    }

    private boolean isAbstractType(ProducedType t) {
        if (t.getDeclaration() instanceof Class) {
            return ((Class) t.getDeclaration()).isAbstract();
        }
        else if (t.getDeclaration() instanceof TypeParameter) {
            return ((TypeParameter) t.getDeclaration()).getParameterList()==null;
        }
        else {
            return true;
        }
    }

    @Override public void visit(Tree.Expression that) {
        //i.e. this is a parenthesized expression
        super.visit(that);
        Tree.Term term = that.getTerm();
        if (term==null) {
            that.addError("expression not well formed");
        }
        else {
            ProducedType t = term.getTypeModel();
            if (t==null) {
                //that.addError("could not determine type of expression");
            }
            else {
                that.setTypeModel(t);
            }
        }
    }
    
    @Override public void visit(Tree.SpecifierOrInitializerExpression that) {
        //i.e. this is a parenthesized expression
        super.visit(that);
        Tree.Term term = that.getExpression()==null ? 
                null : that.getExpression().getTerm();
        if (term!=null) {
            //TODO: it seems a bit fragile to handle this here
            if (term instanceof Tree.StaticMemberOrTypeExpression) {
                Tree.StaticMemberOrTypeExpression smte = (Tree.StaticMemberOrTypeExpression) term;
                if (isGeneric(smte.getDeclaration())) {
                    if (smte.getTypeArguments() instanceof Tree.InferredTypeArguments) {
                        smte.addError("missing type arguments to: " + 
                                smte.getDeclaration().getName(unit));
                    }
                }
            }
        }
    }
    
    @Override public void visit(Tree.Outer that) {
        ProducedType ci = getOuterClassOrInterface(that.getScope());
        if (ci==null) {
            that.addError("outer appears outside a nested class or interface definition");
        }
        else {
            that.setTypeModel(ci);
        }
        /*if (defaultArgument) {
            that.addError("reference to outer from default argument expression");
        }*/
    }

    private boolean inExtendsClause = false;

    @Override public void visit(Tree.Super that) {
        if (inExtendsClause) {
            ClassOrInterface ci = getContainingClassOrInterface(that.getScope());
            if (ci!=null) {
                if (ci.isClassOrInterfaceMember()) {
                    ClassOrInterface s = (ClassOrInterface) ci.getContainer();
                    ProducedType t = s.getExtendedType();
                    //TODO: type arguments??
                    that.setTypeModel(t);
                }
            }
        }
        else {
            ClassOrInterface ci = getContainingClassOrInterface(that.getScope());
            //TODO: for consistency, move these errors to SelfReferenceVisitor
            if (ci==null) {
                that.addError("super appears outside a class definition");
            }
            else if (!(ci instanceof Class)) {
                that.addError("super appears inside an interface definition");
            }
            else {
                ProducedType t = ci.getExtendedType();
                //TODO: type arguments
                that.setTypeModel(t);
            }
        }
        if (defaultArgument) {
            that.addError("reference to super from default argument expression");
        }
    }
    
    @Override public void visit(Tree.This that) {
        ClassOrInterface ci = getContainingClassOrInterface(that.getScope());
        if (ci==null) {
            that.addError("this appears outside a class or interface definition");
        }
        else {
            that.setTypeModel(ci.getType());
        }
        /*if (defaultArgument) {
            that.addWarning("references to this from default argument expressions not yet supported");
        }*/
    }
    
    private ProducedType getTupleType(List<Tree.PositionalArgument> es, 
            boolean requireSequential) {
        ProducedType result = unit.getEmptyDeclaration().getType();
        ProducedType ut = unit.getNothingDeclaration().getType();
        for (int i=es.size()-1; i>=0; i--) {
            Tree.PositionalArgument a = es.get(i);
            ProducedType t = a.getTypeModel();
            if (t!=null) {
                ProducedType et = unit.denotableType(t);
                if (a instanceof Tree.SpreadArgument) {
                    /*if (requireSequential) { 
                        checkSpreadArgumentSequential((Tree.SpreadArgument) a, et);
                    }*/
                    ut = unit.getIteratedType(et);
                    result = spreadType(et, unit, requireSequential);
                }
                else if (a instanceof Tree.Comprehension) {
                    ut = et;
                    Tree.ForComprehensionClause fcc = ((Tree.Comprehension) a).getForComprehensionClause();
                    result = fcc.getPossiblyEmpty() ? 
                            unit.getSequentialType(et) : 
                            unit.getSequenceType(et);
                    if (!requireSequential) {
                        ProducedType it = producedType(unit.getIterableDeclaration(), 
                                et, fcc.getFirstTypeModel());
                        result = intersectionType(result, it, unit);
                    }
                }
                else {
                    ut = unionType(ut, et, unit);
                    result = producedType(unit.getTupleDeclaration(), ut, et, result);
                }
            }
        }
        return result;
    }
    
    private ProducedType spreadType(ProducedType et, Unit unit,
            boolean requireSequential) {
        if (et==null) return null;
        if (unit.isSequentialType(et)) {
            // if it's already a subtype of Sequential, erase 
            // out extraneous information, like that it is a
            // String, just keeping information about what
            // kind of tuple it is
            List<ProducedType> elementTypes = getTupleElementTypes(et);
            boolean variadic = isTupleLengthUnbounded(et);
            boolean atLeastOne = isTupleVariantAtLeastOne(et);
            int minimumLength = getTupleMinimumLength(et);
            if (variadic) {
                ProducedType spt = elementTypes.get(elementTypes.size()-1);
                elementTypes.set(elementTypes.size()-1, unit.getIteratedType(spt));
            }
            return unit.getTupleType(elementTypes, variadic, 
                    atLeastOne, minimumLength);
        }
        else {
            // transform any Iterable into a Sequence without
            // losing the information that it is nonempty, in
            // the case that we know that for sure
            ProducedType st = unit.isNonemptyIterableType(et) ?
                    unit.getSequenceType(unit.getIteratedType(et)) :
                    unit.getSequentialType(unit.getIteratedType(et)); 
            // unless this is a tuple constructor, remember
            // the original Iterable type arguments, to
            // account for the possibility that the argument
            // to Absent is a type parameter
            return requireSequential ? 
                    st : intersectionType(st, et, unit);
        }

    }
    
    @Override public void visit(Tree.Dynamic that) {
        super.visit(that);
        if (!dynamic) {
            that.addError("dynamic instantiation expression occurs outside dynamic block");
        }
    }
    
    @Override public void visit(Tree.Tuple that) {
        super.visit(that);
        ProducedType tt = null;
        Tree.SequencedArgument sa = that.getSequencedArgument();
        if (sa!=null) {
            tt = getTupleType(sa.getPositionalArguments(), true);
        }
        else {
            tt = unit.getEmptyDeclaration().getType();
        }
        if (tt!=null) that.setTypeModel(tt);
    }

    @Override public void visit(Tree.SequenceEnumeration that) {
        super.visit(that);
        ProducedType st = null;
        Tree.SequencedArgument sa = that.getSequencedArgument();
        if (sa!=null) {
            List<Tree.PositionalArgument> pas = sa.getPositionalArguments();
            st = getTupleType(pas, false)
                    .getSupertype(unit.getIterableDeclaration());
        }
        else {
            st = unit.getEmptyDeclaration().getType();
        }
        
        if (st!=null) that.setTypeModel(st);
    }

    /*private ProducedType getGenericElementType(List<Tree.Expression> es,
            Tree.Ellipsis ell) {
        List<ProducedType> list = new ArrayList<ProducedType>();
        for (int i=0; i<es.size(); i++) {
            Tree.Expression e = es.get(i);
            if (e.getTypeModel()!=null) {
                ProducedType et = e.getTypeModel();
                if (i==es.size()-1 && ell!=null) {
                    ProducedType it = unit.getIteratedType(et);
                    if (it==null) {
                        ell.addError("last element expression is not iterable: " +
                                et.getProducedTypeName(unit) + " is not an iterable type");
                    }
                    else {
                        addToUnion(list, it);
                    }
                }
                else {
                    addToUnion(list, unit.denotableType(e.getTypeModel()));
                }
            }
        }
        if (list.isEmpty()) {
            return unit.getBooleanDeclaration().getType();
        }
        else if (list.size()==1) {
            return list.get(0);
        }
        else {
            UnionType ut = new UnionType(unit);
            ut.setExtendedType( unit.getObjectDeclaration().getType() );
            ut.setCaseTypes(list);
            return ut.getType(); 
        }
    }*/

    @Override public void visit(Tree.CatchVariable that) {
        super.visit(that);
        Tree.Variable var = that.getVariable();
        if (var!=null) {
            ProducedType et = unit.getExceptionDeclaration().getType();
            if (var.getType() instanceof Tree.LocalModifier) {
                var.getType().setTypeModel(et);
                var.getDeclarationModel().setType(et);
            }
            else {
                checkAssignable(var.getType().getTypeModel(), et, 
                        var.getType(), 
                        "catch type must be an exception type");
            }
        }
    }
    
    @Override public void visit(Tree.StringTemplate that) {
        super.visit(that);
        for (Tree.Expression e: that.getExpressions()) {
            ProducedType et = e.getTypeModel();
            if (!et.isUnknown()) {
                checkAssignable(et, unit.getObjectDeclaration().getType(), e, 
                        "interpolated expression must not be an optional type: " + 
                        et.getProducedTypeName(unit) + 
                        " is not assignable to Object");
            }
        }
        setLiteralType(that, unit.getStringDeclaration());
    }
    
    @Override public void visit(Tree.StringLiteral that) {
        setLiteralType(that, unit.getStringDeclaration());
    }
    
    @Override public void visit(Tree.NaturalLiteral that) {
        setLiteralType(that, unit.getIntegerDeclaration());
    }
    
    @Override public void visit(Tree.FloatLiteral that) {
        setLiteralType(that, unit.getFloatDeclaration());
    }
    
    @Override public void visit(Tree.CharLiteral that) {
        String result = that.getText();
        if (result.codePointCount(1, result.length()-1)!=1) {
            that.addError("character literal must contain exactly one character");
        }
        setLiteralType(that, unit.getCharacterDeclaration());
    }
    
    @Override public void visit(Tree.QuotedLiteral that) {
        setLiteralType(that, unit.getStringDeclaration());
    }
    
    private void setLiteralType(Tree.Atom that, TypeDeclaration languageType) {
        that.setTypeModel(languageType.getType());
    }
    
    @Override
    public void visit(Tree.CompilerAnnotation that) {
        //don't visit the argument       
    }
    
    @Override
    public void visit(Tree.MatchCase that) {
        super.visit(that);
        for (Tree.Expression e: that.getExpressionList().getExpressions()) {
            ProducedType t = e.getTypeModel();
            if (!isTypeUnknown(t)) {
                TypeDeclaration dec = t.getDeclaration();
                if (!dec.isToplevel() || !dec.isAnonymous()) {
                    e.addError("case must refer to a toplevel object declaration");
                }
                if (switchExpression!=null) {
                    ProducedType st = switchExpression.getTypeModel();
                    if (st!=null && !st.isUnknown()) {
                        if (!hasUncheckedNulls(switchExpression.getTerm()) || !isNullCase(t)) {
                            checkAssignable(t, st, e, 
                                    "case must be assignable to switch expression type");
                        }
                    }
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.SatisfiesCase that) {
        super.visit(that);
        that.addWarning("satisfies cases are not yet supported");
    }
    
    @Override
    public void visit(Tree.IsCase that) {
        Tree.Type t = that.getType();
        if (t!=null) {
            t.visit(this);
        }
        Tree.Variable v = that.getVariable();
        if (v!=null) {
            v.visit(this);
            initOriginalDeclaration(v);
        }
        if (switchExpression!=null) {
            ProducedType st = switchExpression.getTypeModel();
            if (t!=null && st!=null && !st.isUnknown()) {
                ProducedType pt = t.getTypeModel();
                checkReified(that, pt);
                ProducedType it = intersectionType(pt, st, unit);
                if (!hasUncheckedNulls(switchExpression.getTerm()) || !isNullCase(pt)) {
                    if (it.isExactly(unit.getNothingDeclaration().getType())) {
                        that.addError("narrows to Nothing type: " + 
                                pt.getProducedTypeName(unit) + " has empty intersection with " + 
                                st.getProducedTypeName(unit));
                    }
                    /*checkAssignable(ct, switchType, cc.getCaseItem(), 
                        "case type must be a case of the switch type");*/
                }
                if (v!=null) {
                    v.getType().setTypeModel(it);
                    v.getDeclarationModel().setType(it);
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.SwitchStatement that) {
        Tree.Expression ose = switchExpression;
        switchExpression = that.getSwitchClause().getExpression();
        super.visit(that);        
        switchExpression = ose;
        
    }
    
    @Override
    public void visit(Tree.SwitchCaseList that) {
        super.visit(that);
        if (switchExpression!=null) {
            boolean hasIsCase = false;
            for (Tree.CaseClause cc: that.getCaseClauses()) {
                if (cc.getCaseItem() instanceof Tree.IsCase) {
                    hasIsCase = true;
                }
                ProducedType ct = getType(cc);
                if (!isTypeUnknown(ct)) {
                    for (Tree.CaseClause occ: that.getCaseClauses()) {
                        if (occ==cc) break;
                        ProducedType oct = getType(occ);
                        if (!isTypeUnknown(oct)) {
                            //TODO: the following test doesn't work for cases of an interface!
                            if (!intersectionType(ct, oct, unit)
                                    .isExactly(unit.getNothingDeclaration().getType())) {
                                cc.getCaseItem().addError("cases are not disjoint: " + 
                                    ct.getProducedTypeName(unit) + " and " + 
                                        oct.getProducedTypeName(unit));
                            }
                        }
                    }
                }
            }
            if (hasIsCase) {
                Tree.Term st = switchExpression.getTerm();
                if (st instanceof Tree.BaseMemberExpression) {
                    checkReferenceIsNonVariable((Tree.BaseMemberExpression) st);
                }
                else {
                    switchExpression.addError("switch expression must be a value reference in switch with type cases");
                }
            }   
        }
        

        if (that.getElseClause()==null && switchExpression!=null) {
            ProducedType st = switchExpression.getTypeModel();
            if (st!=null) {
                //form the union of all the case types
                List<ProducedType> list = new ArrayList<ProducedType>();
                for (Tree.CaseClause cc: that.getCaseClauses()) {
                    ProducedType ct = getType(cc);
                    if (isTypeUnknown(ct)) {
                        return; //Note: early exit!
                    }
                    else {
                        addToUnion(list, ct);
                    }
                }
                UnionType ut = new UnionType(unit);
                ut.setCaseTypes(list);
                //if the union of the case types covers 
                //the switch expression type then the 
                //switch is exhaustive
                if (!ut.getType().covers(st) && 
                        !(st.getDeclaration() instanceof UnknownType)) {
                    that.addError("case types must cover all cases of the switch type or an else clause must appear: " +
                            ut.getType().getProducedTypeName(unit) + " does not cover " + st.getProducedTypeName(unit));
                }
            }
        }
        
    }
    
    private boolean isNullCase(ProducedType ct) {
        TypeDeclaration d = ct.getDeclaration();
        return d!=null && d instanceof Class &&
                d.equals(unit.getNullDeclaration());
    }

    private ProducedType getType(Tree.CaseClause cc) {
        Tree.CaseItem ci = cc.getCaseItem();
        if (ci instanceof Tree.IsCase) {
            Tree.Type t = ((Tree.IsCase) ci).getType();
            if (t!=null) {
                return t.getTypeModel().getUnionOfCases();
            }
            else {
                return null;
            }
        }
        else if (ci instanceof Tree.MatchCase) {
            List<ProducedType> list = new ArrayList<ProducedType>();
            for (Tree.Expression e: ((Tree.MatchCase) ci).getExpressionList().getExpressions()) {
                if (e.getTypeModel()!=null) {
                    addToUnion(list, e.getTypeModel());
                }
            }
            return formUnion(list);
        }
        else {
            return null;
        }
    }
    
    @Override
    public void visit(Tree.TryCatchStatement that) {
        super.visit(that);
        for (Tree.CatchClause cc: that.getCatchClauses()) {
            if (cc.getCatchVariable()!=null && 
                    cc.getCatchVariable().getVariable()!=null) {
                ProducedType ct = cc.getCatchVariable()
                        .getVariable().getType().getTypeModel();
                if (ct!=null) {
                    for (Tree.CatchClause ecc: that.getCatchClauses()) {
                        if (ecc.getCatchVariable()!=null &&
                                ecc.getCatchVariable().getVariable()!=null) {
                            if (cc==ecc) break;
                            ProducedType ect = ecc.getCatchVariable()
                                    .getVariable().getType().getTypeModel();
                            if (ect!=null) {
                                if (ct.isSubtypeOf(ect)) {
                                    cc.getCatchVariable().getVariable().getType()
                                            .addError("exception type is already handled by earlier catch clause:" 
                                                    + ct.getProducedTypeName(unit));
                                }
                                if (ct.getDeclaration() instanceof UnionType) {
                                    for (ProducedType ut: ct.getDeclaration().getCaseTypes()) {
                                        if ( ut.substitute(ct.getTypeArguments()).isSubtypeOf(ect) ) {
                                            cc.getCatchVariable().getVariable().getType()
                                                    .addError("exception type is already handled by earlier catch clause: "
                                                            + ut.getProducedTypeName(unit));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.DynamicStatement that) {
        boolean od = dynamic;
        dynamic = true;
        super.visit(that);
        dynamic = od;
    }
    
    private boolean acceptsTypeArguments(Declaration member, List<ProducedType> typeArguments, 
            Tree.TypeArguments tal, Node parent) {
        return acceptsTypeArguments(null, member, typeArguments, tal, parent);
    }
    
    private static boolean isGeneric(Declaration member) {
        return member instanceof Generic && 
            !((Generic) member).getTypeParameters().isEmpty();
    }
    
    private boolean acceptsTypeArguments(ProducedType receiver, Declaration dec, 
            List<ProducedType> typeArguments, Tree.TypeArguments tal, Node parent) {
        if (dec==null) return false;
        if (isGeneric(dec)) {
            List<TypeParameter> params = ((Generic) dec).getTypeParameters();
            int min = 0;
            for (TypeParameter tp: params) { 
                if (!tp.isDefaulted()) min++;
            }
            int max = params.size();
            int args = typeArguments.size();
            if (args<=max && args>=min) {
                for (int i=0; i<args; i++) {
                    TypeParameter param = params.get(i);
                    ProducedType argType = typeArguments.get(i);
                    //Map<TypeParameter, ProducedType> self = Collections.singletonMap(param, arg);
                    boolean argTypeMeaningful = argType!=null && 
                            !(argType.getDeclaration() instanceof UnknownType);
                    for (ProducedType st: param.getSatisfiedTypes()) {
                        //sts = sts.substitute(self);
                        ProducedType sts = st.getProducedType(receiver, dec, typeArguments);
                        if (argType!=null) {
                            if (!isCondition && !argType.isSubtypeOf(sts)) {
                                if (argTypeMeaningful) {
                                    if (tal instanceof Tree.InferredTypeArguments) {
                                        parent.addError("inferred type argument " + argType.getProducedTypeName(unit)
                                                + " to type parameter " + param.getName()
                                                + " of declaration " + dec.getName(unit)
                                                + " not assignable to upper bound " + sts.getProducedTypeName(unit));
                                    }
                                    else {
                                        ((Tree.TypeArgumentList) tal).getTypes()
                                        .get(i).addError("type parameter " + param.getName() 
                                                + " of declaration " + dec.getName(unit)
                                                + " has argument " + argType.getProducedTypeName(unit) 
                                                + " not assignable to upper bound " + sts.getProducedTypeName(unit), 2102);
                                    }
                                }
                                return false;
                            }
                        }
                    }
                    if (!isCondition && 
                            !argumentSatisfiesEnumeratedConstraint(receiver, dec, 
                                    typeArguments, argType, param)) {
                        if (argTypeMeaningful) {
                            if (tal instanceof Tree.InferredTypeArguments) {
                                parent.addError("inferred type argument " + argType.getProducedTypeName(unit)
                                        + " to type parameter " + param.getName()
                                        + " of declaration " + dec.getName(unit)
                                        + " not one of the enumerated cases");
                            }
                            else {
                                ((Tree.TypeArgumentList) tal).getTypes()
                                .get(i).addError("type parameter " + param.getName() 
                                        + " of declaration " + dec.getName(unit)
                                        + " has argument " + argType.getProducedTypeName(unit) 
                                        + " not one of the enumerated cases");
                            }
                        }
                        return false;
                    }
                }
                return true;
            }
            else {
                if (tal==null || tal instanceof Tree.InferredTypeArguments) {
                    parent.addError("requires type arguments: " + dec.getName(unit));
                }
                else {
                    String help="";
                    if (args<min) {
                        help = " (requires at least " + min + " type arguments)";
                    }
                    else if (args>max) {
                        help = " (allows at most " + max + " type arguments)";
                    }
                    tal.addError("wrong number of type arguments to: " + 
                            dec.getName(unit) + help);
                }
                return false;
            }
        }
        else {
            boolean empty = typeArguments.isEmpty();
            if (!empty) {
                tal.addError("does not accept type arguments: " + 
                        dec.getName(unit));
            }
            return empty;
        }
    }

    private static boolean argumentSatisfiesEnumeratedConstraint(ProducedType receiver, 
            Declaration member, List<ProducedType> typeArguments, ProducedType argType,
            TypeParameter param) {
        
        List<ProducedType> caseTypes = param.getCaseTypes();
        if (caseTypes==null || caseTypes.isEmpty()) {
            //no enumerated constraint
            return true;
        }
        
        //if the type argument is a subtype of one of the cases
        //of the type parameter then the constraint is satisfied
        for (ProducedType ct: caseTypes) {
            ProducedType cts = ct.getProducedType(receiver, member, typeArguments);
            if (argType.isSubtypeOf(cts)) {
                return true;
            }
        }

        //if the type argument is itself a type parameter with
        //an enumerated constraint, and every enumerated case
        //is a subtype of one of the cases of the type parameter,
        //then the constraint is satisfied
        if (argType.getDeclaration() instanceof TypeParameter) {
            List<ProducedType> argCaseTypes = argType.getDeclaration().getCaseTypes();
            if (argCaseTypes!=null && !argCaseTypes.isEmpty()) {
                for (ProducedType act: argCaseTypes) {
                    boolean foundCase = false;
                    for (ProducedType ct: caseTypes) {
                        ProducedType cts = ct.getProducedType(receiver, member, typeArguments);
                        if (act.isSubtypeOf(cts)) {
                            foundCase = true;
                            break;
                        }
                    }
                    if (!foundCase) {
                        return false;
                    }
                }
                return true;
            }
        }

        return false;
    }

    @Override 
    public void visit(Tree.ExtendedType that) {
        inExtendsClause = true;
        super.visit(that);
        inExtendsClause = false;

        TypeDeclaration td = (TypeDeclaration) that.getScope();
        Tree.SimpleType et = that.getType();
        if (et!=null) {
            ProducedType type = et.getTypeModel();
            if (type!=null) {
                checkSelfTypes(et, td, type);
                checkExtensionOfMemberType(et, td, type);
                //checkCaseOfSupertype(et, td, type);
                if (!td.isAlias()) {
                    TypeDeclaration etd = td.getExtendedTypeDeclaration();
                    while (etd!=null && etd.isAlias()) {
                        etd = etd.getExtendedTypeDeclaration();
                    }
                    if (etd!=null) {
                        if (etd.isFinal()) {
                            et.addError("extends a final class: " + 
                                    etd.getName(unit));
                        }
                        else if (!etd.isExtendable() && 
                                !inLanguageModule(that.getUnit())) {
                            et.addError("directly extends a special language type: " +
                                    etd.getName(unit));
                        }
                    }
                }
            }
        }
    }
    
    @Override 
    public void visit(Tree.SatisfiedTypes that) {
        super.visit(that);
        TypeDeclaration td = (TypeDeclaration) that.getScope();
        Set<TypeDeclaration> set = new HashSet<TypeDeclaration>();
        for (Tree.StaticType t: that.getTypes()) {
            ProducedType type = t.getTypeModel();
            if (type!=null) {
                type = type.resolveAliases();
                if (!set.add(type.getDeclaration())) {
                    //this error is not really truly necessary
                    //but the spec says it is an error, and
                    //the backend doesn't like it
                    t.addError("duplicate satisfied type: " + td.getName(unit));
                }
                checkSelfTypes(t, td, type);
                checkExtensionOfMemberType(t, td, type);
                /*if (!(td instanceof TypeParameter)) {
                    checkCaseOfSupertype(t, td, type);
                }*/
            }
        }
        //Moved to RefinementVisitor, which 
        //handles this kind of stuff:
        /*if (td instanceof TypeParameter) {
            List<ProducedType> list = new ArrayList<ProducedType>();
            for (ProducedType st: td.getSatisfiedTypes()) {
                addToIntersection(list, st, unit);
            }
            IntersectionType it = new IntersectionType(unit);
            it.setSatisfiedTypes(list);
            if (it.getType().getDeclaration() instanceof NothingType) {
                that.addError("upper bound constraints cannot be satisfied by any type except Nothing");
            }
        }*/
    }

    /*void checkCaseOfSupertype(Tree.StaticType t, TypeDeclaration td,
            ProducedType type) {
        //TODO: I think this check is a bit too restrictive, since 
        //      it doesn't allow intermediate types between the 
        //      enumerated type and the case type, but since the
        //      similar check below doesn't work, we need it
        if (type.getDeclaration().getCaseTypes()!=null) {
            for (ProducedType ct: type.getDeclaration().getCaseTypes()) {
                if (ct.substitute(type.getTypeArguments())
                        .isExactly(td.getType())) {
                    return;
                }
            }
            t.addError("not a case of supertype: " + 
                    type.getDeclaration().getName(unit));
        }
    }*/

    @Override 
    public void visit(Tree.CaseTypes that) {
        super.visit(that);
        //this forces every case to be a subtype of the
        //enumerated type, so that we can make use of the
        //enumerated type is equivalent to its cases
        TypeDeclaration td = (TypeDeclaration) that.getScope();
        
        //TODO: get rid of this awful hack:
        List<ProducedType> cases = td.getCaseTypes();
        td.setCaseTypes(null);
        
        if (!(td instanceof TypeParameter)) {
            for (Tree.StaticType t: that.getTypes()) {
                ProducedType type = t.getTypeModel();
                if (!(type.getDeclaration() instanceof TypeParameter)) {
                    //it's not a self type
                    if (type!=null) {
                        checkAssignable(type, td.getType(), t, 
                                "case type must be a subtype of enumerated type");
                        //note: this is a better, faster way to call 
                        //      validateEnumeratedSupertypeArguments()
                        //      but unfortunately it winds up displaying
                        //      the error on the wrong node, confusing
                        //      the user
                        /*ProducedType supertype = type.getDeclaration().getType().getSupertype(td);
                        validateEnumeratedSupertypeArguments(t, type.getDeclaration(), supertype);*/
                    }
                }
            }
            for (Tree.BaseMemberExpression bme: that.getBaseMemberExpressions()) {
                ProducedType type = bme.getTypeModel();
                if (type!=null) {
                    checkAssignable(type, td.getType(), bme, 
                            "case type must be a subtype of enumerated type");
                }
            }
        }
        
        //TODO: get rid of this awful hack:
        td.setCaseTypes(cases);
    }

    private void checkExtensionOfMemberType(Node that, TypeDeclaration td,
            ProducedType type) {
        ProducedType qt = type.getQualifyingType();
        if (qt!=null && td instanceof ClassOrInterface) {
            Scope s = td;
            while (s!=null) {
                s = s.getContainer();
                if (s instanceof TypeDeclaration) {
                    TypeDeclaration otd = (TypeDeclaration) s;
                    if ( otd.getType().isSubtypeOf(qt) ) {
                        return;
                    }
                }
            }
            that.addError("containing type " + qt.getDeclaration().getName(unit) + 
                    " of supertype " + type.getDeclaration().getName(unit) + 
                    " is not an outer type or supertype of any outer type of " +
                    td.getName());
        }
    }
    
    private void checkSelfTypes(Tree.StaticType that, TypeDeclaration td, ProducedType type) {
        if (!(td instanceof TypeParameter)) { //TODO: is this really ok?!
            List<TypeParameter> params = type.getDeclaration().getTypeParameters();
            List<ProducedType> args = type.getTypeArgumentList();
            for (int i=0; i<params.size(); i++) {
                TypeParameter param = params.get(i);
                if ( param.isSelfType() && args.size()>i ) {
                    ProducedType arg = args.get(i);
                    if (arg==null) arg = new UnknownType(unit).getType();
                    TypeDeclaration std = param.getSelfTypedDeclaration();
                    ProducedType at;
                    if (param.getContainer().equals(std)) {
                        at = td.getType();
                    }
                    else {
                        //TODO: lots wrong here?
                        TypeDeclaration mtd = (TypeDeclaration) td.getMember(std.getName(), null, false);
                        at = mtd==null ? null : mtd.getType();
                    }
                    if (at!=null && !at.isSubtypeOf(arg) && 
                            !(td.getSelfType()!=null && 
                                td.getSelfType().isExactly(arg))) {
                        String help;
                        TypeDeclaration ad = arg.getDeclaration();
                        if (ad instanceof TypeParameter &&
                                ((TypeParameter) ad).getDeclaration().equals(td)) {
                            help = " (try making " + ad.getName() + " a self type of " + td.getName() + ")";
                        }
                        else if (ad instanceof Interface) {
                            help = " (try making " + td.getName() + " satisfy " + ad.getName() + ")";
                        }
                        else if (ad instanceof Class && td instanceof Class) {
                            help = " (try making " + td.getName() + " extend " + ad.getName() + ")";
                        }
                        else {
                            help = "";
                        }
                        that.addError("type argument does not satisfy self type constraint on type parameter " +
                                param.getName() + " of " + type.getDeclaration().getName(unit) + ": " +
                                arg.getProducedTypeName(unit) + " is not a supertype or self type of " + 
                                td.getName(unit) + help);
                    }
                }
            }
        }
    }

    private void validateEnumeratedSupertypes(Node that, Class d) {
        ProducedType type = d.getType();
        for (ProducedType supertype: type.getSupertypes()) {
            if (!type.isExactly(supertype)) {
                TypeDeclaration std = supertype.getDeclaration();
                if (std.getCaseTypes()!=null && !std.getCaseTypes().isEmpty()) {
                    if (std.getCaseTypes().size()==1 && 
                            std.getCaseTypeDeclarations().get(0).isSelfType()) {
                        continue;
                    }
                    List<ProducedType> types=new ArrayList<ProducedType>();
                    for (ProducedType ct: std.getCaseTypes()) {
                        ProducedType cst = type.getSupertype(ct.getDeclaration());
                        if (cst!=null) {
                            types.add(cst);
                        }
                    }
                    if (types.isEmpty()) {
                        that.addError("concrete type is not a subtype of any case of enumerated supertype: " + 
                                d.getName(unit) + " is a subtype of " + std.getName(unit));
                    }
                    else if (types.size()>1) {
                        StringBuilder sb = new StringBuilder();
                        for (ProducedType pt: types) {
                            sb.append(pt.getProducedTypeName(unit)).append(" and ");
                        }
                        sb.setLength(sb.length()-5);
                        that.addError("concrete type is a subtype of multiple cases of enumerated supertype: " + 
                                d.getName(unit) + " is a subtype of " + sb);
                    }
                }
            }
        }
    }

    private void validateEnumeratedSupertypeArguments(Node that, ClassOrInterface d) {
        //note: I hate doing the whole traversal here, but it is the
        //      only way to get the error in the right place (see
        //      the note in visit(CaseTypes) for more)
        ProducedType type = d.getType();
        for (ProducedType supertype: type.getSupertypes()) { //traverse the entire supertype hierarchy of the declaration
            if (!type.isExactly(supertype)) {
                List<TypeDeclaration> ctds = supertype.getDeclaration().getCaseTypeDeclarations();
                if (ctds!=null) {
                    for (TypeDeclaration ct: ctds) {
                        if (ct.equals(d)) { //the declaration is a case of the current enumerated supertype
                            validateEnumeratedSupertypeArguments(that, d, supertype);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void validateEnumeratedSupertypeArguments(Node that, TypeDeclaration d, 
            ProducedType supertype) {
        for (TypeParameter p: supertype.getDeclaration().getTypeParameters()) {
            ProducedType arg = supertype.getTypeArguments().get(p); //the type argument that the declaration (indirectly) passes to the enumerated supertype
            if (arg!=null) {
                validateEnumeratedSupertypeArgument(that, d, supertype, p, arg);
            }
        }
    }

    private void validateEnumeratedSupertypeArgument(Node that, TypeDeclaration d, 
            ProducedType supertype, TypeParameter p, ProducedType arg) {
        TypeDeclaration td = arg.getDeclaration();
        if (td instanceof TypeParameter) {
            TypeParameter tp = (TypeParameter) td;
            if (tp.getDeclaration().equals(d)) { //the argument is a type parameter of the declaration
                //check that the variance of the argument type parameter is
                //the same as the type parameter of the enumerated supertype
                if (p.isCovariant() && !tp.isCovariant()) {
                    that.addError("argument to covariant type parameter of supertype must be covariant: " + 
                            typeDescription(p, unit));
                }
                if (p.isContravariant() && !tp.isContravariant()) {
                    that.addError("argument to contravariant type parameter of supertype must be contravariant: " + 
                            typeDescription(p, unit));
                }
            }
            else {
                that.addError("argument to type parameter of enumerated supertype must be a type parameter of " +
                        d.getName() + ": " + typeDescription(p, unit));
            }
        }
        else if (p.isCovariant()) {
            if (!(td instanceof NothingType)) {
                //TODO: let it be the union of the lower bounds on p
                that.addError("argument to covariant type parameter of enumerated supertype must be a type parameter or Nothing: " + 
                        typeDescription(p, unit));
            }
        }
        else if (p.isContravariant()) {
            List<ProducedType> sts = p.getSatisfiedTypes();
            //TODO: do I need to do type arg substitution here??
            ProducedType ub = formIntersection(sts);
            if (!(arg.isExactly(ub))) {
                that.addError("argument to contravariant type parameter of enumerated supertype must be a type parameter or " + 
                        typeNamesAsIntersection(sts, unit) + ": " + 
                        typeDescription(p, unit));
            }
        }
        else {
            that.addError("argument to type parameter of enumerated supertype must be a type parameter: " + 
                    typeDescription(p, unit));
        }
    }
    
    @Override public void visit(Tree.Term that) {
        super.visit(that);
        if (that.getTypeModel()==null) {
            that.setTypeModel( defaultType() );
        }
    }

    @Override public void visit(Tree.Type that) {
        super.visit(that);
        if (that.getTypeModel()==null) {
            that.setTypeModel( defaultType() );
        }
    }

    private ProducedType defaultType() {
        TypeDeclaration ut = new UnknownType(unit);
        ut.setExtendedType(unit.getAnythingDeclaration().getType());
        return ut.getType();
    }
    
}
