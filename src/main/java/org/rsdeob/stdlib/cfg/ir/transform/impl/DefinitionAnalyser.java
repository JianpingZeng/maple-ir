package org.rsdeob.stdlib.cfg.ir.transform.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.objectweb.asm.tree.MethodNode;
import org.rsdeob.stdlib.cfg.edge.FlowEdge;
import org.rsdeob.stdlib.cfg.ir.StatementGraph;
import org.rsdeob.stdlib.cfg.ir.stat.CopyVarStatement;
import org.rsdeob.stdlib.cfg.ir.stat.Statement;
import org.rsdeob.stdlib.cfg.ir.stat.SyntheticStatement;
import org.rsdeob.stdlib.cfg.ir.transform.ForwardsFlowAnalyser;
import org.rsdeob.stdlib.collections.NullPermeableHashMap;
import org.rsdeob.stdlib.collections.SetCreator;

public class DefinitionAnalyser extends ForwardsFlowAnalyser<Statement, FlowEdge<Statement>, NullPermeableHashMap<String, Set<CopyVarStatement>>> {

	private final NullPermeableHashMap<String, Set<CopyVarStatement>> initial;
	
	public DefinitionAnalyser(StatementGraph graph, MethodNode m) {
		super(graph);
		initial = newState();
//		defineInputs(m);
	}
	
//	private void defineInputs(MethodNode m) {
//		// build the entry in sets
//		Type[] args = Type.getArgumentTypes(m.desc);
//		int index = 0;
//		if((m.access & Opcodes.ACC_STATIC) == 0) {
//			addEntry(index, Type.getType(m.owner.name));
//			index++;
//		}
//	
//		for(int i=0; i < args.length; i++) {
//			Type arg = args[i];
//			addEntry(index, arg);
//			index += arg.getSize();
//		}
//	}
//	
//	private void addEntry(int index, Type type) {
//		CopyVarStatement stmt = selfDefine(new VarExpression(index, type));
//		String name = stmt.getVariable().toString();
//		initial.getNonNull(name).add(stmt);
//	}
//	
//	private CopyVarStatement selfDefine(VarExpression var) {
//		return new CopyVarStatement(var, var);
//	}

	@Override
	public void remove(Statement n) {
		super.remove(n);
		
		if(n instanceof CopyVarStatement) {
			CopyVarStatement cvs = (CopyVarStatement) n;
			
			for(Statement s : in.keySet()) {
				NullPermeableHashMap<String, Set<CopyVarStatement>> in1 = in(s);
				for(Set<CopyVarStatement> set : in1.values()) {
					set.remove(cvs);
				}
			}
			for(Statement s : out.keySet()) {
				NullPermeableHashMap<String, Set<CopyVarStatement>> out1 = out(s);
				for(Set<CopyVarStatement> set : out1.values()) {
					set.remove(cvs);
				}
			}
		}
	}
	
	@Override
	protected NullPermeableHashMap<String, Set<CopyVarStatement>> newState() {
		return new NullPermeableHashMap<>(new SetCreator<>());
	}

	@Override
	protected NullPermeableHashMap<String, Set<CopyVarStatement>> newEntryState() {
		NullPermeableHashMap<String, Set<CopyVarStatement>> map = newState();
		copy(initial, map);
		return map;
	}

	@Override
	protected void merge(NullPermeableHashMap<String, Set<CopyVarStatement>> in1, NullPermeableHashMap<String, Set<CopyVarStatement>> in2, NullPermeableHashMap<String, Set<CopyVarStatement>> out) {
		for(Entry<String, Set<CopyVarStatement>> e : in1.entrySet()) {
			out.getNonNull(e.getKey()).addAll(e.getValue());
		}
		for(Entry<String, Set<CopyVarStatement>> e : in2.entrySet()) {
			out.getNonNull(e.getKey()).addAll(e.getValue());
		}
	}

	@Override
	protected void copy(NullPermeableHashMap<String, Set<CopyVarStatement>> src, NullPermeableHashMap<String, Set<CopyVarStatement>> dst) {
		for(Entry<String, Set<CopyVarStatement>> e : src.entrySet()) {
			dst.getNonNull(e.getKey()).addAll(e.getValue());
		}
	}

	@Override
	protected boolean equals(NullPermeableHashMap<String, Set<CopyVarStatement>> s1, NullPermeableHashMap<String, Set<CopyVarStatement>> s2) {
		Set<String> keys = new HashSet<>();
		keys.addAll(s1.keySet());
		keys.addAll(s2.keySet());
		
		for(String key : keys) {
			if(!s1.containsKey(key) || !s2.containsKey(key)) {
				return false;
			}
			
			Set<CopyVarStatement> set1 = s1.get(key);
			Set<CopyVarStatement> set2 = s2.get(key);
			
			if(!set1.equals(set2)) {
				return false;
			}
		}
		
//		{
//			System.out.println("  equals: ");
//			System.out.println("    " + s1);
//			System.out.println("    " + s2);
//		}
		
		return true;
	}

	@Override
	protected void propagate(Statement n, NullPermeableHashMap<String, Set<CopyVarStatement>> in, NullPermeableHashMap<String, Set<CopyVarStatement>> out) {
		
		// create a new set here because if we don't, future operations will
		// affect the in and the out sets. basically don't use out.putAll(in) here.
		for(Entry<String, Set<CopyVarStatement>> e : in.entrySet()) {
			out.put(e.getKey(), new HashSet<>(e.getValue()));
		}
		
		// final VarExpression rhs;
		
		if(n instanceof SyntheticStatement) {
			n = ((SyntheticStatement) n).getStatement();
		}
		
		if(n instanceof CopyVarStatement) {
			CopyVarStatement stmt = (CopyVarStatement) n;
			String name = stmt.getVariable().toString();
			Set<CopyVarStatement> set = out.get(name);
			if(set == null) {
				set = new HashSet<>();
				out.put(name, set);
			}
			set.clear();
			set.add(stmt);
			// rhs = stmt.getVariable();
		} else {
			// rhs = null;
		}
		
		System.out.println(" propagating " + n);
//		{
//			List<String> keys = new ArrayList<>(in.keySet());
//			Collections.sort(keys);
//			System.out.println("  IN:");
//			for(String key : keys) {
//				System.out.println("     " + key + " = " + in.get(key));
//			}
//			keys = new ArrayList<>(out.keySet());
//			Collections.sort(keys);
//			System.out.println("  OUT:");
//			for(String key : keys) {
//				System.out.println("     " + key + " = " + out.get(key));
//			}
//		}
	}

	public Set<Statement> getUses(CopyVarStatement d) {
		HashSet<Statement> uses = new HashSet<>();
		for (Map.Entry<Statement, NullPermeableHashMap<String, Set<CopyVarStatement>>> entry : in.entrySet())
			if (entry.getValue().get(d.getVariable().toString()).contains(d))
				uses.add(entry.getKey());
		return uses;
	}
}