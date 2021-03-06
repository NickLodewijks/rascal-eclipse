package org.rascalmpl.eclipse.editor.highlight;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.rascalmpl.eclipse.Activator;
import org.rascalmpl.eclipse.editor.commands.AbstractEditorAction;
import org.rascalmpl.eclipse.nature.ProjectEvaluatorFactory;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.value.IConstructor;
import org.rascalmpl.value.IString;

import io.usethesource.impulse.editor.UniversalEditor;
import io.usethesource.impulse.parser.IParseController;

abstract class HighlightAction extends AbstractEditorAction {

	private String function;
	private String ext;

	public HighlightAction(UniversalEditor editor, String label, String function, String ext) {
		super(editor, label);
		this.function = function;
		this.ext = ext;
	}
		
	@Override
	public void run() {
		IConstructor tree = (IConstructor) ((IParseController) editor
				.getParseController()).getCurrentAst();
		if (tree != null) {
			Evaluator eval = ProjectEvaluatorFactory.getInstance()
					.getEvaluator(project);
			eval.doImport(null, "util::Highlight");
			IString s = (IString) eval.call(function, tree);
			IWorkbenchPage page = PlatformUI.getWorkbench()
					.getActiveWorkbenchWindow().getActivePage();
			try {
				page.openEditor(new StringInput(new StringStorage(editor, project, s.getValue(), ext)),
						"org.eclipse.ui.DefaultTextEditor");
			} catch (PartInitException e) {
				Activator.getInstance().logException("could not open editor for " + ext, e);
			}
		}

	}

}
