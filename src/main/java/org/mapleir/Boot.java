package org.mapleir;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;

import org.mapleir.byteio.CompleteResolvingJarDumper;
import org.mapleir.deobimpl2.CallgraphPruningPass;
import org.mapleir.deobimpl2.ConstantExpressionReorderPass;
import org.mapleir.deobimpl2.ConstantParameterPass;
import org.mapleir.deobimpl2.FieldRSADecryptionPass;
import org.mapleir.ir.ControlFlowGraphDumper;
import org.mapleir.ir.cfg.BoissinotDestructor;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.Expr;
import org.mapleir.stdlib.IContext;
import org.mapleir.stdlib.call.CallTracer;
import org.mapleir.stdlib.deob.ICompilerPass;
import org.mapleir.stdlib.klass.ClassTree;
import org.mapleir.stdlib.klass.InvocationResolver;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.topdank.byteengineer.commons.data.JarInfo;
import org.topdank.byteio.in.SingleJarDownloader;
import org.topdank.byteio.out.JarDumper;

public class Boot {

	private static Map<MethodNode, ControlFlowGraph> cfgs;
	private static long timer;
	private static Deque<String> sections;
	
	private static double lap() {
		long now = System.nanoTime();
		long delta = now - timer;
		timer = now;
		return (double)delta / 1_000_000_000L;
	}
	
	private static void section0(String endText, String sectionText) {
		if(sections.isEmpty()) {
			lap();
			System.out.println(sectionText);
		} else {
			/* remove last section. */
			sections.pop();
			System.out.printf(endText, lap());
			System.out.println("\n" + sectionText);
		}

		/* push the new one. */
		sections.push(sectionText);
	}
	
	private static void section(String text) {
		section0("...took %fs.%n", text);
	}
	
	public static void main2(String[] args) {
		BigInteger benc = BigInteger.valueOf(29);
		int e1 = benc.intValue();
		BigInteger bdec = BigInteger.valueOf(1332920885);
		int d1 = bdec.intValue();
		
		BigInteger benc2 = BigInteger.valueOf(101);
		int e2 = benc2.intValue();
		BigInteger bdec2 = FieldRSADecryptionPass.inverse(benc2, false);
		int d2 = bdec2.intValue();
		
		int k = 10;
		int f1 = 6 * e1;
		int f2 = 7 * e2;
		
		f1 = (f2 * (d2 * e1)) + (k * e1);
		
		System.out.println(f1 * d1);
		
		
		if("".equals("")) {
			return;
		}
	}
	
	public static void main(String[] args) throws IOException {
		cfgs = new HashMap<>();
		sections = new LinkedList<>();
		/* if(args.length < 1) {
			System.err.println("Usage: <rev:int>");
			System.exit(1);
			return;
		} */
		
		File f = locateRevFile(129);
		
		section("Preparing to run on " + f.getAbsolutePath());
		SingleJarDownloader<ClassNode> dl = new SingleJarDownloader<>(new JarInfo(f));
		dl.download();
		
		section("Building jar class hierarchy.");
		ClassTree tree = new ClassTree(dl.getJarContents().getClassContents());
		
		section("Initialising context.");

		InvocationResolver resolver = new InvocationResolver(tree);
		IContext cxt = new IContext() {
			@Override
			public ClassTree getClassTree() {
				return tree;
			}

			@Override
			public ControlFlowGraph getIR(MethodNode m) {
				if(cfgs.containsKey(m)) {
					return cfgs.get(m);
				} else {
					ControlFlowGraph cfg = ControlFlowGraphBuilder.build(m);
					cfgs.put(m, cfg);
					return cfg;
				}
			}

			@Override
			public Set<MethodNode> getActiveMethods() {
				return cfgs.keySet();
			}

			@Override
			public InvocationResolver getInvocationResolver() {
				return resolver;
			}
		};
		
		section("Expanding callgraph and generating cfgs.");
		CallTracer tracer = new IRCallTracer(cxt) {
			@Override
			protected void processedInvocation(MethodNode caller, MethodNode callee, Expr call) {
				/* the cfgs are generated by calling IContext.getIR()
				 * in IRCallTracer.traceImpl(). */
			}
		};
		for(MethodNode m : findEntries(tree)) {
			tracer.trace(m);
		}
		
		section0("...generated " + cfgs.size() + " cfgs in %fs.%n", "Preparing to transform.");
		
		runPasses(cxt, getTransformationPasses());
		
		section("Retranslating SSA IR to standard flavour.");
		for(Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
			MethodNode mn = e.getKey();
			ControlFlowGraph cfg = e.getValue();
			
			BoissinotDestructor.leaveSSA(cfg);
			cfg.getLocals().realloc(cfg);
			ControlFlowGraphDumper.dump(cfg, mn);
		}
		
		section("Rewriting jar.");
		JarDumper dumper = new CompleteResolvingJarDumper(dl.getJarContents());
		dumper.dump(new File("out/osb.jar"));
		
		section("Finished.");
	}
	
	private static void runPasses(IContext cxt, ICompilerPass[] passes) {
		List<ICompilerPass> completed = new ArrayList<>();
		ICompilerPass last = null;
		
		for(int i=0; i < passes.length; i++) {
			ICompilerPass p = passes[i];
			section0("...took %fs." + (i == 0 ? "%n" : ""), "Running " + p.getId());
			p.accept(cxt, last, completed);
			
			completed.add(p);
			last = p;
		}
	}
	
	private static ICompilerPass[] getTransformationPasses() {
		return new ICompilerPass[] {
				new CallgraphPruningPass(),
				new ConstantParameterPass(),
				new ConstantExpressionReorderPass(),
				new FieldRSADecryptionPass()
		};
	}
	
	private static File locateRevFile(int rev) {
		return new File("res/gamepack" + rev + ".jar");
	}
	
	private static Set<MethodNode> findEntries(ClassTree tree) {
		Set<MethodNode> set = new HashSet<>();
		for(ClassNode cn : tree.getClasses().values())  {
			for(MethodNode m : cn.methods) {
				if(m.name.length() > 2) {
					set.add(m);
				}
			}
		}
		return set;
	}
}