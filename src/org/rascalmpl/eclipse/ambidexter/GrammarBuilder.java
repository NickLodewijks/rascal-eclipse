package org.rascalmpl.eclipse.ambidexter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nl.cwi.sen1.AmbiDexter.AmbiDexterConfig;
import nl.cwi.sen1.AmbiDexter.grammar.CharacterClass;
import nl.cwi.sen1.AmbiDexter.grammar.FollowRestrictions;
import nl.cwi.sen1.AmbiDexter.grammar.Grammar;
import nl.cwi.sen1.AmbiDexter.grammar.NonTerminal;
import nl.cwi.sen1.AmbiDexter.grammar.Production;
import nl.cwi.sen1.AmbiDexter.grammar.Symbol;
import nl.cwi.sen1.AmbiDexter.util.LinkedList;

import org.eclipse.imp.pdb.facts.IConstructor;
import org.eclipse.imp.pdb.facts.IInteger;
import org.eclipse.imp.pdb.facts.IList;
import org.eclipse.imp.pdb.facts.IMap;
import org.eclipse.imp.pdb.facts.IRelation;
import org.eclipse.imp.pdb.facts.ISet;
import org.eclipse.imp.pdb.facts.ITuple;
import org.eclipse.imp.pdb.facts.IValue;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.rascalmpl.interpreter.asserts.ImplementationError;


public class GrammarBuilder {

	private Grammar g;
	private Set<IConstructor> conditionals = new HashSet<IConstructor>();
	
	public void getSymbolNames(IConstructor grammar, List<String> startSymbols, List<String> otherSymbols) {
		IMap rules = (IMap) grammar.get("rules");
		Iterator<Entry<IValue, IValue>> i = rules.entryIterator();
		while (i.hasNext()) {
			Entry<IValue, IValue> e = i.next();
			IConstructor symb = (IConstructor) e.getKey();
			if (symb.getName().equals("start")) {
				startSymbols.add(symb.toString());
			} else {
				otherSymbols.add(symb.toString());
			}
		}
	}
	
	public Grammar build(IConstructor grammar, IRelation nestingRestr, AmbiDexterConfig config) throws InvalidInputException {
		g = new Grammar(grammar.getName(), true, config.doRejects, config.doFollowRestrictions);
		
		IMap rules = (IMap) grammar.get("rules");
		Map<IConstructor, Production> prodMap = new HashMap<IConstructor, Production>();

		Iterator<Entry<IValue, IValue>> i = rules.entryIterator();
		while (i.hasNext()) {
			Entry<IValue, IValue> e = i.next();
			IConstructor symb = (IConstructor) e.getKey();
			IConstructor prods = (IConstructor) e.getValue();
			
			NonTerminal nt = (NonTerminal) getSymbol(symb);
			addProd(nt, prods, prodMap);
		}
		
		addPriorities(nestingRestr, prodMap);		
		addConditions();
		
		g.setNewStartSymbol(config.alternativeStartSymbol);

		g.finish();
		g.verify();
		g.printSize();
		
		return g;
	}

	private void addPriorities(IRelation nestingRestr,
			Map<IConstructor, Production> prodMap) {
		for (IValue e : nestingRestr) {
			ITuple tup = (ITuple) e;
			Production father = prodMap.get(tup.get(0));
			int pos = ((IInteger)tup.get(1)).intValue();
			Production child = prodMap.get(tup.get(2));
			
			father.addDeriveRestriction(pos, child);
		}
	}
	
	void addProd(NonTerminal nt, IConstructor prod, Map<IConstructor, Production> prodMap) {
//		data Production 
//		= \choice(Symbol def, set[Production] alternatives)
//		| \priority(Symbol def, list[Production] choices)
//		| \associativity(Symbol def, Associativity \assoc, set[Production] alternatives)

//		data Production 
//		= prod(Symbol def, list[Symbol] symbols, set[Attr] attributes) 
//		| regular(Symbol def)

		String name = prod.getName();
		if (name.equals("choice")) {
			ISet alts = (ISet) prod.get("alternatives");
			for (IValue e : alts) {
				addProd(nt, (IConstructor) e, prodMap);
			}			
		} else if (name.equals("priority")) {
			IList choices = (IList) prod.get("choices");
			for (IValue e : choices) {
				addProd(nt, (IConstructor) e, prodMap);
			}
		} else if (name.equals("associativity")) {
			ISet alts = (ISet) prod.get("alternatives");
			for (IValue e : alts) {
				addProd(nt, (IConstructor) e, prodMap);
			}
		} else if (name.equals("prod")) {
			Production p = g.newProduction(nt); 
			
			IList lhs = (IList) prod.get("symbols");
			for (IValue e : lhs) {
				p.addSymbol(getSymbol((IConstructor) e));
			}
			
			g.addProduction(p);
			prodMap.put(prod, p);
		} else if (name.equals("regular")) {
			// do nothing, the regular symbols are expanded already
		} else {
			throw new ImplementationError("Unknown Production constructor " + name);
		}
	}
	
	Symbol getSymbol(IConstructor symbol) {
//		@doc{The start symbol wraps any symbol to indicate it will occur at the top}
//		data Symbol = \start(Symbol symbol);
//
//		@doc{These symbols are the named non-terminals}
//		data Symbol 
//		  = \sort(str name)  
//		  | \lex(str name) 
//		  | \layouts(str name) 
//		  | \keywords(str name)
//		  | \parameterized-sort(str name, list[Symbol] parameters)  
//		  | \parameter(str name)
//		  | \label(str name, Symbol symbol)
//		  ; 
//
//		@doc{These are the terminal symbols}
//		data Symbol 
//		  = \lit(str string) 
//		  | \cilit(str string)
//		  | \char-class(list[CharRange] ranges)
//		  ;
//		    
//		@doc{These are the regular expressions}
//		data Symbol
//		  = \empty()  
//		  | \opt(Symbol symbol)  
//		  | \iter(Symbol symbol)   
//		  | \iter-star(Symbol symbol)   
//		  | \iter-seps(Symbol symbol, list[Symbol] separators)   
//		  | \iter-star-seps(Symbol symbol, list[Symbol] separators) 
//		  | \alt(set[Symbol] alternatives)
//		  | \seq(list[Symbol] sequence)
//		  ;
//		  
//		@doc{The conditional wrapper adds conditions to the existance of an instance of a symbol}
//		data Symbol = \conditional(Symbol symbol, set[Condition] conditions);
		
		Symbol s = null;
		
		String name = symbol.getName();
		if (name.equals("label")) {
			s = getSymbol((IConstructor) symbol.get(1));
		} else if (name.equals("char-class")) {
			IList ranges = (IList) symbol.get("ranges");
			CharacterClass cc = new CharacterClass(ranges.length() * 2, 0);
//			data CharRange = range(int begin, int end);
			for (IValue e : ranges) {
				IConstructor r = (IConstructor) e;
				cc.append(((IInteger) r.get("begin")).intValue(), ((IInteger) r.get("end")).intValue());
			}
			s = cc;
		} else if (name.equals("conditional")) {
			s = g.nonTerminals.get(symbol.toString());
			if (s == null) {
				s = g.getNonTerminal(symbol.toString());
				Production p = g.newProduction((NonTerminal) s);
				p.addSymbol(getSymbol((IConstructor) symbol.get("symbol")));
				g.addProduction(p);
				
				conditionals.add(symbol);
			}
		} else {
			s = g.getNonTerminal(symbol.toString());
		}
		
		return s;
	}

	private void addConditions() {
//		@doc{Conditions on symbols give rise to disambiguation filters.}    
//		data Condition
//		  = \follow(Symbol symbol)
//		  | \not-follow(Symbol symbol)
//		  | \precede(Symbol symbol)
//		  | \not-precede(Symbol symbol)
//		  | \delete(Symbol symbol)
//		  | \at-column(int column) 
//		  | \begin-of-line()  
//		  | \end-of-line()  
//		  ;
		
		for (IConstructor symbol : conditionals) {
			NonTerminal n = (NonTerminal) getSymbol(symbol);
			for (IValue e : (ISet) symbol.get("conditions")) {
				IConstructor cond = (IConstructor) e;
				String cname = cond.getName();
				if (cname.equals("not-follow")) {
					FollowRestrictions fr = new FollowRestrictions();
					IConstructor r = (IConstructor) cond.get("symbol");
					if (r.getName().equals("char-class")) {
						CharacterClass cc = (CharacterClass) getSymbol(r);
						fr.add(new LinkedList<CharacterClass>(cc));
					} else {
						// literal
						NonTerminal lit = (NonTerminal) getSymbol(r);
						Production p = lit.productions.iterator().next();
						LinkedList<CharacterClass> ll = null;
						for (int i = p.getLength() - 1; i >= 0; i--) {
							ll = new LinkedList<CharacterClass>((CharacterClass) p.getSymbolAt(i), ll);
						}
						fr.add(ll);
					}
					n.addFollowRestrictions(fr);
				} else if (cname.equals("follow")) {
					// Implement as inverted not-follow.
					// This is not exactly the same, but it causes no problems for the NU test,
					// since it remains conservative.
					// For the derivation generation it does produce some spurious ambiguous strings.
					FollowRestrictions fr = new FollowRestrictions();
					IConstructor r = (IConstructor) cond.get("symbol");
					if (r.getName().equals("char-class")) {
						CharacterClass cc = (CharacterClass) getSymbol(r);
						fr.add(new LinkedList<CharacterClass>(cc.invert()));
					} else {
						// literal
						NonTerminal lit = (NonTerminal) getSymbol(r);
						Production p = lit.productions.iterator().next();
						for (int j = 0; j < p.getLength(); j++) {
							CharacterClass last = (CharacterClass) p.getSymbolAt(j);
							LinkedList<CharacterClass> ll = new LinkedList<CharacterClass>(last.invert());
							for (int i = j - 1; i >= 0; i--) {
								ll = new LinkedList<CharacterClass>((CharacterClass) p.getSymbolAt(i), ll);
							}
							fr.add(ll);
						}
					}
					n.addFollowRestrictions(fr);
				} else if (cname.equals("delete")) { // reject
					Production reject = g.newProduction(n);
					reject.reject  = true;
					reject.addSymbol(getSymbol((IConstructor) cond.get("symbol")));
					g.addProduction(reject);
				}
				// TODO add other conditions
			}
		}
	}
}
