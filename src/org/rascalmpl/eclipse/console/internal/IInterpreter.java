package org.rascalmpl.eclipse.console.internal;

import java.io.PrintWriter;

/**
 * Interpreters should implement this.
 * 
 * @author Arnold Lankamp
 */
public interface IInterpreter{
	
	/**
	 * Initializes the console.
	 */
	void initialize();
	
	/**
	 * Requests the interpreter to execute the given command.
	 * 
	 * @param command
	 *          The command to execute.
	 * @return True if the command was completed; false if it wasn't.
	 * @throws CommandExecutionException
	 *          Thrown when an exception occurs during the processing of a command. The message
	 *          contained in the exception will be printed in the console.
	 * @throws TerminationException
	 *          Thrown when the executed command triggers a termination request for the console.
	 */
	boolean execute(String command) throws CommandExecutionException, TerminationException;
	
	/**
	 * Associated the given console this this interpreter. This method is called during the
	 * initialization of the console.
	 * 
	 * @param console
	 *          The console to associate with this interpreter.
	 */
	void setConsole(IInterpreterConsole console);
	
	/**
	 * Returns the output that was generated by the last executed command.
	 * 
	 * @return The output that was generated by the last executed command.
	 */
	String getOutput();
	
	/**
	 * Requests the interpreter to terminate. This method is called by the console; users are
	 * discouraged from calling this method, instead IInterpreterConsole#terminate() should be
	 * used.
	 */
	void terminate();
	
	/**
	 * Requests the interpreter to stop what it is doing now and return to an initial state.
	 */
	void interrupt();
	
	/**
	 * Gives the interpreter the command to persist the given history.
	 * 
	 * @param history
	 *          The command history associated with the console.
	 */
	void storeHistory(CommandHistory history);

	/**
	 * Change the stdout writer
	 * @param printStream
	 */
	void setStdOut(PrintWriter w);
	
	/**
	 * Change the stderr writer
	 * @param printStream
	 */
	void setStdErr(PrintWriter w);

	
}
