/*******************************************************************************
 * Copyright (c) 2006, 2007 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Markus Schorn - initial API and implementation
 *******************************************************************************/ 

package org.eclipse.cdt.internal.ui.typehierarchy;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.DOMException;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.ICompositeType;
import org.eclipse.cdt.core.dom.ast.IEnumeration;
import org.eclipse.cdt.core.dom.ast.IEnumerator;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.ITypedef;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPMember;
import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.IFunctionDeclaration;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.cdt.core.model.IWorkingCopy;
import org.eclipse.cdt.ui.CUIPlugin;

import org.eclipse.cdt.internal.ui.editor.CEditor;
import org.eclipse.cdt.internal.ui.util.ExceptionHandler;
import org.eclipse.cdt.internal.ui.viewsupport.FindNameForSelectionVisitor;
import org.eclipse.cdt.internal.ui.viewsupport.IndexUI;

public class TypeHierarchyUI {
	public static THViewPart open(ICElement input, IWorkbenchWindow window) {
        if (!isValidInput(input)) {
        	return null;
        }
        ICElement memberInput= null;
        if (!isValidTypeInput(input)) {
        	memberInput= input;
        	input= memberInput.getParent();
        	if (!isValidTypeInput(input)) {
        		ICElement[] inputs= findInput(memberInput);
        		if (inputs != null) {
        			input= inputs[0];
        			memberInput= inputs[1];
        		}
        	}
        }
        		
        if (isValidTypeInput(input)) {
        	return openInViewPart(window, input, memberInput);
        }
        return null;
    }

    private static THViewPart openInViewPart(IWorkbenchWindow window, ICElement input, ICElement member) {
        IWorkbenchPage page= window.getActivePage();
        try {
            THViewPart result= (THViewPart)page.showView(CUIPlugin.ID_TYPE_HIERARCHY);
            result.setInput(input, member);
            return result;
        } catch (CoreException e) {
            ExceptionHandler.handle(e, window.getShell(), Messages.TypeHierarchyUI_OpenTypeHierarchy, null); 
        }
        return null;        
    }

    public static void open(final CEditor editor, final ITextSelection sel) {
		if (editor != null) {
			final ICProject project= editor.getInputCElement().getCProject();
			final IEditorInput editorInput = editor.getEditorInput();
			final Display display= Display.getCurrent();
			
			Job job= new Job(Messages.TypeHierarchyUI_OpenTypeHierarchy) {
				protected IStatus run(IProgressMonitor monitor) {
					try {
						final ICElement[] elems= findInput(project, editorInput, sel);
						if (elems != null && elems.length == 2) {
							display.asyncExec(new Runnable() {
								public void run() {
									openInViewPart(editor.getSite().getWorkbenchWindow(), elems[0], elems[1]);
								}});
						}
						return Status.OK_STATUS;
					} 
					catch (CoreException e) {
						return e.getStatus();
					}
				}
			};
			job.setUser(true);
			job.schedule();
		}
    }
    
	private static ICElement[] findInput(ICProject project, IEditorInput editorInput, ITextSelection sel) throws CoreException {
		try {
			IIndex index= CCorePlugin.getIndexManager().getIndex(project, IIndexManager.ADD_DEPENDENCIES | IIndexManager.ADD_DEPENDENT);

			index.acquireReadLock();
			try {
				IASTName name= getSelectedName(index, editorInput, sel);
				if (name != null) {
					IBinding binding= name.resolveBinding();
					if (!isValidInput(binding)) {
						return null;
					}
					ICElement member= null;
					if (!isValidTypeInput(binding)) {
						member= findDeclaration(project, index, name, binding);
						name= null;
						binding= findTypeBinding(binding);
					}
					if (isValidTypeInput(binding)) {
						ICElement input= findDefinition(project, index, name, binding);
						if (input != null) {
							return new ICElement[] {input, member};
						}
					}
				}
			}
			finally {
				if (index != null) {
					index.releaseReadLock();
				}
			}
		}
		catch (CoreException e) {
			CUIPlugin.getDefault().log(e);
		} 
		catch (DOMException e) {
			CUIPlugin.getDefault().log(e);
		} 
		catch (InterruptedException e) {
		}
		return null;
	}

	private static ICElement[] findInput(ICElement member)  {
		ICProject project= member.getCProject();
		try {
			IIndex index= CCorePlugin.getIndexManager().getIndex(project, IIndexManager.ADD_DEPENDENCIES | IIndexManager.ADD_DEPENDENT);
			index.acquireReadLock();
			try {
				IIndexName name= IndexUI.elementToName(index, member);
				if (name != null) {
					member= IndexUI.getCElementForName(project, index, name);
					IBinding binding= index.findBinding(name);
					binding= findTypeBinding(binding);
					if (isValidTypeInput(binding)) {
						ICElement input= findDefinition(project, index, null, binding);
						if (input != null) {
							return new ICElement[] {input, member};
						}
					}
				}
			}
			finally {
				if (index != null) {
					index.releaseReadLock();
				}
			}
		}
		catch (CoreException e) {
			CUIPlugin.getDefault().log(e);
		} 
		catch (DOMException e) {
			CUIPlugin.getDefault().log(e);
		} 
		catch (InterruptedException e) {
		}
		return null;
	}

	private static IBinding findTypeBinding(IBinding memberBinding) throws DOMException {
		if (memberBinding instanceof IEnumerator) {
			IType type= ((IEnumerator) memberBinding).getType();
			if (type instanceof IBinding) {
				return (IBinding) type;
			}
		}
		else if (memberBinding instanceof ICPPMember) {
			return ((ICPPMember) memberBinding).getClassOwner();
		}
		return null;
	}

	private static ICElement findDefinition(ICProject project, IIndex index,
			IASTName name, IBinding binding) throws CoreException, DOMException {
		if (name != null && name.isDefinition()) {
			return IndexUI.getCElementForName(project, index, name);
		}

		ICElement[] elems= IndexUI.findAllDefinitions(index, binding);
		if (elems.length > 0) {
			return elems[0];
		}
		return IndexUI.findAnyDeclaration(index, project, binding);
	}

	private static ICElement findDeclaration(ICProject project, IIndex index,
			IASTName name, IBinding binding) throws CoreException, DOMException {
		if (name != null && name.isDefinition()) {
			return IndexUI.getCElementForName(project, index, name);
		}

		ICElement[] elems= IndexUI.findAllDefinitions(index, binding);
		if (elems.length > 0) {
			return elems[0];
		}
		return IndexUI.findAnyDeclaration(index, project, binding);
	}

	private static IASTName getSelectedName(IIndex index, IEditorInput editorInput, ITextSelection selection) throws CoreException {
		int selectionStart = selection.getOffset();
		int selectionLength = selection.getLength();

		IWorkingCopy workingCopy = CUIPlugin.getDefault().getWorkingCopyManager().getWorkingCopy(editorInput);
		if (workingCopy == null)
			return null;
		
		int options= ITranslationUnit.AST_SKIP_INDEXED_HEADERS;
		IASTTranslationUnit ast = workingCopy.getAST(index, options);
		FindNameForSelectionVisitor finder= new FindNameForSelectionVisitor(ast.getFilePath(), selectionStart, selectionLength);
		ast.accept(finder);
		return finder.getSelectedName();
	}

	public static boolean isValidInput(IBinding binding) {
		if (isValidTypeInput(binding)
				|| binding instanceof ICPPMember
				|| binding instanceof IEnumerator) {
			return true;
		}
		return false;
	}

	public static boolean isValidTypeInput(IBinding binding) {
		if (binding instanceof ICompositeType
				|| binding instanceof IEnumeration 
				|| binding instanceof ITypedef) {
			return true;
		}
		return false;
	}

	public static boolean isValidInput(ICElement elem) {
		if (elem == null) {
			return false;
		}
		if (isValidTypeInput(elem)) {
			return true;
		}
		switch (elem.getElementType()) {
		case ICElement.C_FIELD:
		case ICElement.C_METHOD:
		case ICElement.C_METHOD_DECLARATION:
		case ICElement.C_TEMPLATE_METHOD:
		case ICElement.C_TEMPLATE_METHOD_DECLARATION:
		case ICElement.C_ENUMERATOR:
			return true;
		}
		return false;
	}

	public static boolean isValidTypeInput(ICElement elem) {
		if (elem == null) {
			return false;
		}
		switch (elem.getElementType()) {
		case ICElement.C_CLASS:
		case ICElement.C_STRUCT:
		case ICElement.C_UNION:
		case ICElement.C_CLASS_DECLARATION:
		case ICElement.C_STRUCT_DECLARATION:
		case ICElement.C_UNION_DECLARATION:
		case ICElement.C_ENUMERATION:
		case ICElement.C_TYPEDEF:
			return true;
		}
		return false;
	}

	static String getLocalElementSignature(ICElement element) {
		if (element != null) {
			try {
				switch (element.getElementType()) {
				case ICElement.C_METHOD:
				case ICElement.C_METHOD_DECLARATION:
					return ((IFunctionDeclaration) element).getSignature();
				case ICElement.C_FIELD:
					return element.getElementName();
				}
			} catch (CModelException e) {
				CUIPlugin.getDefault().log(e);
			}
		}
		return null;
	}
}
