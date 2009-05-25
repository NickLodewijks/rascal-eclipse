package org.meta_environment.rascal.eclipse.lib.jdt;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.imp.pdb.facts.IMap;
import org.eclipse.imp.pdb.facts.IMapWriter;
import org.eclipse.imp.pdb.facts.IRelationWriter;
import org.eclipse.imp.pdb.facts.ISourceLocation;
import org.eclipse.imp.pdb.facts.IString;
import org.eclipse.imp.pdb.facts.ITuple;
import org.eclipse.imp.pdb.facts.IValueFactory;
import org.eclipse.imp.pdb.facts.type.Type;
import org.eclipse.imp.pdb.facts.type.TypeFactory;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.meta_environment.ValueFactoryFactory;
import org.meta_environment.rascal.interpreter.control_exceptions.Throw;

public class JDTImporter extends ASTVisitor {

	private static final IWorkspaceRoot ROOT = ResourcesPlugin.getWorkspace().getRoot();
    private static final IValueFactory VF = ValueFactoryFactory.getValueFactory();
	private static final TypeFactory TF = TypeFactory.getInstance();
	
	private boolean extractBindings = false;
	private boolean extractTypeInfo = false;
	
	// bindings
	private static final Type locType = TF.tupleType(TF.integerType(), TF.integerType());
	private static final Type bindingTupleType = TF.tupleType(locType, TF.stringType());
	private static final Type bindingRelType = TF.relType(locType, TF.stringType());

	private IRelationWriter typeBindings;
	private IRelationWriter methodBindings;
	private IRelationWriter constructorBindings;
	private IRelationWriter fieldBindings;
	private IRelationWriter variableBindings;

	// type facts
	private static final Type typeFactTupleType = TF.tupleType(TF.stringType(), TF.stringType());
	private static final Type typeFactRelType = TF.relType(TF.stringType(), TF.stringType());

	private IRelationWriter extnds;
	private IRelationWriter implmnts;
	private IRelationWriter declaredMethods;
	private IRelationWriter declaredFields;
	private IRelationWriter declaredTypes;
	
	public JDTImporter() {
		super();
	}
	
	public IMap importBindings(IFile file) {
		typeBindings = VF.relationWriter(bindingTupleType);
		methodBindings = VF.relationWriter(bindingTupleType);
		constructorBindings = VF.relationWriter(bindingTupleType);
		fieldBindings = VF.relationWriter(bindingTupleType);
		variableBindings = VF.relationWriter(bindingTupleType);
		
		extractBindings = true;
		visitCompilationUnit(file);
		
		IMapWriter mw = VF.mapWriter(TF.stringType(), bindingRelType);
		mw.put(VF.string("typeBindings"), typeBindings.done());
		mw.put(VF.string("methodBindings"), methodBindings.done());
		mw.put(VF.string("constructorBindings"), constructorBindings.done());
		mw.put(VF.string("fieldBindings"), fieldBindings.done());
		mw.put(VF.string("variableBindings"), variableBindings.done());
		
		return mw.done();
	}
	
	public IMap importTypeInfo(IFile file) {
		implmnts = VF.relationWriter(typeFactTupleType);
		extnds = VF.relationWriter(typeFactTupleType);
		declaredTypes = VF.relationWriter(typeFactTupleType);
		declaredMethods = VF.relationWriter(typeFactTupleType);
		declaredFields = VF.relationWriter(typeFactTupleType);
		
		extractTypeInfo = true;
		visitCompilationUnit(file);
		
		IMapWriter mw = VF.mapWriter(TF.stringType(), typeFactRelType);
		mw.put(VF.string("implements"), implmnts.done());
		mw.put(VF.string("extends"), extnds.done());
		mw.put(VF.string("declaredTypes"), declaredTypes.done());
		mw.put(VF.string("declaredMethods"), declaredMethods.done());
		mw.put(VF.string("declaredFields"), declaredFields.done());
		
		return mw.done();
	}
	
	private void visitCompilationUnit(IFile file) {
		int i;
		
		ICompilationUnit icu = JavaCore.createCompilationUnitFrom(file);
		
		ASTParser parser = ASTParser.newParser(AST.JLS3);
		parser.setResolveBindings(true);
		parser.setSource(icu);
		
		CompilationUnit cu = (CompilationUnit) parser.createAST(null);
		
		IProblem[] problems = cu.getProblems();
		for (i = 0; i < problems.length; i++) {
			if (problems[i].isError()) {
				throw new Throw(VF.string("Error(s) in compilation unit"), (ISourceLocation) null, null);
			}
			//System.out.println(problems[i].getMessage());
		}
		
		cu.accept(this);
	}
	
	public void preVisit(ASTNode n) {
		if (extractBindings) {
			importBindingInfo(n);
		}
		
		if (extractTypeInfo) {
			importTypeInfo(n);
		}
	}
	
	private void importBindingInfo(ASTNode n) {
	
		// type bindings
		ITypeBinding tb = null;
		
		if (n instanceof org.eclipse.jdt.core.dom.Type) {
			tb = ((org.eclipse.jdt.core.dom.Type)n).resolveBinding();
		} else if (n instanceof AbstractTypeDeclaration) {
			tb = ((AbstractTypeDeclaration) n).resolveBinding();
		} else if (n instanceof AnonymousClassDeclaration) {
			tb = ((AnonymousClassDeclaration) n).resolveBinding();			
		} else if (n instanceof Expression) {
			tb = ((Expression) n).resolveTypeBinding();
		} else if (n instanceof TypeDeclarationStatement) {
			tb = ((TypeDeclarationStatement) n).resolveBinding();
		} else if (n instanceof TypeParameter) {
			tb = ((TypeParameter) n).resolveBinding();
		}
		
		if (tb != null) {
			addBinding(typeBindings, n, importTypeBinding(tb));
		}
		
		// method and constructor bindings
		IMethodBinding mb = null;
		IMethodBinding cb = null;
		
		if (n instanceof ClassInstanceCreation) {
			cb = ((ClassInstanceCreation) n).resolveConstructorBinding();
		} else if (n instanceof ConstructorInvocation) {
			cb = ((ConstructorInvocation) n).resolveConstructorBinding();
		} else if (n instanceof EnumConstantDeclaration) {
			cb = ((EnumConstantDeclaration) n).resolveConstructorBinding();
		} else if (n instanceof MethodDeclaration) {
			mb = ((MethodDeclaration) n).resolveBinding();
		} else if (n instanceof MethodInvocation) {
			mb = ((MethodInvocation) n).resolveMethodBinding();			
		} else if (n instanceof SuperConstructorInvocation) {
			cb = ((SuperConstructorInvocation) n).resolveConstructorBinding();
		} else if (n instanceof SuperMethodInvocation) {
			mb = ((SuperMethodInvocation) n).resolveMethodBinding();
		}
		
		if (mb != null) {
			addBinding(methodBindings, n, importMethodBinding(mb));
		}		
		if (cb != null) {
			addBinding(constructorBindings, n, importMethodBinding(cb));
		}
		
		// field and variable bindings
		IVariableBinding vb = null;
		IVariableBinding fb = null;
		
		if (n instanceof EnumConstantDeclaration) {
			fb = ((EnumConstantDeclaration) n).resolveVariable();
		} else if (n instanceof FieldAccess) {
			fb = ((FieldAccess) n).resolveFieldBinding();
		} else if (n instanceof SuperFieldAccess) {
			fb = ((SuperFieldAccess) n).resolveFieldBinding();
		} else if (n instanceof VariableDeclaration) {
			vb = ((VariableDeclaration) n).resolveBinding();
		} else if (n instanceof Name) {
			try {
				// local variable, parameter or field.
				vb = (IVariableBinding)((Name)n).resolveBinding();
				if (vb.getDeclaringMethod() == null) {
					fb = vb;
					vb = null;
				}
					
			} catch (Exception e) {}
		}
		
		if (fb != null) {
			addBinding(fieldBindings, n, importVariableBinding(fb));
		}
		if (vb != null) {
			addBinding(variableBindings, n, importVariableBinding(vb));
		}
	
		
		// package bindings	
		// these only exists for package declarations, which must use the fully qualified name
		// therefore we skip these
	}
	
	private void importTypeInfo(ASTNode n) {
		if (n instanceof TypeDeclaration) {
			// TBD: generic and parameterized types
			ITypeBinding tb = ((TypeDeclaration) n).resolveBinding();
			IString thisType = VF.string(importTypeBinding(tb));
			
			ITypeBinding superclass = tb.getSuperclass();
			if (superclass != null) {
				ITuple tup = VF.tuple(thisType, VF.string(importTypeBinding(superclass)));
				extnds.insert(tup);
			}
			
			ITypeBinding[] interfaces = tb.getInterfaces();
			for (ITypeBinding interf : interfaces) {
				ITuple tup = VF.tuple(thisType, VF.string(importTypeBinding(interf)));
				if (tb.isClass()) {
					implmnts.insert(tup);
				} else {
					extnds.insert(tup);
				}
			}
			
			ITypeBinding[] innertypes = tb.getDeclaredTypes();
			for (ITypeBinding innertype : innertypes) {
				ITuple tup = VF.tuple(thisType, VF.string(importTypeBinding(innertype)));
				declaredTypes.insert(tup);
			}
			
			IMethodBinding[] methods = tb.getDeclaredMethods();
			for (IMethodBinding method : methods) {
				ITuple tup = VF.tuple(thisType, VF.string(importMethodBinding(method)));
				declaredMethods.insert(tup);
			}
			
			IVariableBinding[] fields = tb.getDeclaredFields();
			for (IVariableBinding field : fields) {
				ITuple tup = VF.tuple(thisType, VF.string(importVariableBinding(field)));
				declaredFields.insert(tup);
			}
		}
		//EnumDeclaration
		//EnumConstantDeclaration
		//FieldDeclaration
		//MethodDeclaration
		//Initializer
		
		//parameters?
		
		//local variables?
		
		//scopes? not in JDT :(

		//calls? not in JDT :(
	}
	
	String importTypeBinding(ITypeBinding tb) {
		// TBD: generic and parameterized types
		// TBD: might return an empty string if there is no qualified name, see JavaDoc!
		return tb.getQualifiedName();
	}
	
	String importMethodBinding(IMethodBinding mb) {
		String s = mb.getDeclaringClass().getQualifiedName();
		s += "." + mb.getName() + "(";
		
		ITypeBinding[] tbs = mb.getParameterTypes();
		for (int i = 0; i < tbs.length; i++) {
			s += tbs[i].getQualifiedName();
			if (i < tbs.length-1) {
				s += ", ";
			}
		}
		
		s += ")";
		
		return s;
	}
	
	String importVariableBinding(IVariableBinding vb) {
		//TBD: static imports?
		String s = "";
		IMethodBinding mb = vb.getDeclaringMethod();
		if (mb != null) {
			s += importMethodBinding(mb) + ".";
		} else {
			s += vb.getDeclaringClass().getQualifiedName() + ".";
		}
		s += vb.getName();

		return s;
	}

	void addBinding(IRelationWriter rw, ASTNode n, String value) {				
		ITuple loc = VF.tuple(VF.integer(n.getStartPosition()), VF.integer(n.getLength()));
		ITuple fact = VF.tuple(loc, VF.string(value));
		
		rw.insert(fact);
	}
}
