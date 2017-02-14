package org.mapleir.ir.cfg.builder;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.tree.AbstractInsnNode.*;

import java.util.*;
import java.util.Map.Entry;

import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.edge.ConditionalJumpEdge;
import org.mapleir.ir.cfg.edge.DefaultSwitchEdge;
import org.mapleir.ir.cfg.edge.ImmediateEdge;
import org.mapleir.ir.cfg.edge.SwitchEdge;
import org.mapleir.ir.cfg.edge.TryCatchEdge;
import org.mapleir.ir.cfg.edge.UnconditionalJumpEdge;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.ExpressionStack;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.*;
import org.mapleir.ir.code.expr.ArithmeticExpr.Operator;
import org.mapleir.ir.code.expr.ComparisonExpr.ValueComparisonType;
import org.mapleir.ir.code.stmt.*;
import org.mapleir.ir.code.stmt.ConditionalJumpStmt.ComparisonType;
import org.mapleir.ir.code.stmt.MonitorStmt.MonitorMode;
import org.mapleir.ir.code.stmt.copy.CopyVarStmt;
import org.mapleir.ir.locals.Local;
import org.mapleir.stdlib.collections.graph.GraphUtils;
import org.mapleir.stdlib.collections.graph.flow.ExceptionRange;
import org.mapleir.stdlib.util.TypeUtils;
import org.mapleir.stdlib.util.TypeUtils.ArrayType;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

public class GenerationPass extends ControlFlowGraphBuilder.BuilderPass {

	private static final boolean VERIFY = false;
	
	/* Format for stack configs:
	 *  OPCODE:NUM_CONFIGS:CONF_0:CONF_2 ... :CONF_N
	 *  
	 * Each config has the following format:
	 *  h_n: expected stack object height
	 *     options: C, B, S, I, F, D, J, Z, O(subclass of Ljava/lang/Object;),
	 *              N(null const), E(subclass of Ljava/lang/Exception;),
	 *              *(anything), 1(1, 2 or 4 byte word), 2(8 byte word).
	 *              O shadows N, X(any single stack obj).
	 *              Prefixing a full type name, O or E with # 
	 *              will match the exact class. 
	 *              All of the above with the exception of N(null) can
	 *              be prepended with array dimension markers ([).
	 *      object pointers count as 1 byte when considering 
	 *       stack deltas.
	 *  h_0| ... |h_n
	 * where h_0 is the top element of the stack
	 * and h_n is the n-1th element of the stack
	 * from the top. 
	 *  
	 *  before>after where before and after
	 *     are both stack configurations.
	 *  
	 * Example:
	 *   1:1:*>I|*    describes ACONST_NULL,
	 *      => opcode = 1;
	 *      => 1 configuration
	 *      => no required pre stack configuration
	 *      => top item ofpost stack is an int
	 *  */
	private static final String[] STACK_CONFIGS = new String[] {
			"0:0",        // NOP
			"1:1:*>N|*",  // ACONST_NULL
			"2:1:*>I|*",  // ICONST_M1
			"3:1:*>I|*",  // ICONST_0
			"4:1:*>I|*",  // ICONST_1
			"5:1:*>I|*",  // ICONST_2
			"6:1:*>I|*",  // ICONST_3
			"7:1:*>I|*",  // ICONST_4
			"8:1:*>I|*",  // ICONST_5
			"9:1:*>J|*",  // LCONST_0
			"10:1:*>J|*", // LCONST_1
			"11:1:*>F|*", // FCONST_0
			"12:1:*>F|*", // FCONST_1
			"13:1:*>F|*", // FCONST_2
			"14:1:*>D|*", // DCONST_0
			"15:1:*>D|*", // DCONST_1
			
			// B/S IPUSH is sign extended before
			// being pushed... >:(
			"16:1:*>I|*", // BIPUSH
			"17:1:*>I|*", // SIPUSH
			"18:1:*>X|*", // LDC
			
			"21:1:*>I|*", // ILOAD
			"22:1:*>J|*", // LLOAD
			"23:1:*>F|*", // FLOAD
			"24:1:*>D|*", // DLOAD
			"25:1:*>X|*", // ALOAD
			
			"46:1:I|[I|*>I|*", // IALOAD
			"47:1:I|[J|*>J|*", // LALOAD
			"48:1:I|[F|*>F|*", // FALOAD
			"49:1:I|[D|*>D|*", // DALOAD
			"50:1:I|[X|*>X|*", // AALOAD
			// B/S ALOAD are signed extended to
			// int before value is pushed and
			// CALOAD is zero extended, also as
			// an int.
			"51:1:I|[B|*>I|*", // BALOAD
			"52:1:I|[C|*>I|*", // CALOAD
			"53:1:I|[S|*>I|*", // SALOAD
			
			"54:1:I>*", // ISTORE
			"55:1:J*>*", // LSTORE
			"56:1:F*>*", // FSTORE
			"57:1:D*>*", // DSTORE
			"58:1:X*>*", // ASTORE
			
			"79:1:I|I|[I|*>*", // IASTORE
			"80:1:J|I|[J|*>*", // LASTORE
			"81:1:F|I|[F|*>*", // FASTORE
			"82:1:D|I|[D|*>*", // DASTORE
			"83:1:X|I|[X|*>*", // AASTORE
			// B/C/S ASTORE values are ints
			// and are truncated to byte/char/
			// /short before being set in the
			// array.
			"84:1:I|I|[B|*>*", // BASTORE
			"85:1:I|I|[C|*>*", // CASTORE
			"86:1:I|I|[S|*>*", // SASTORE
			
			"87:1:1|*>*", // POP
			"88:2:1|1|*>*:2|*>*", // POP2
			// Stack operations are handled in
			// their respective generation
			// methods.
			"89:0", // DUP
			"90:0",   // DUP_X1
			"91:0",   // DUP_X2
			"92:0",   // DUP2
			"93:0",   // DUP2_X1
			"94:0",   // DUP2_X2
			"95:0",    // SWAP
			
			// 96 - 131
			"96:1:I|I|*>I|*", // IADD
			"97:1:J|J|*>J|*", // LADD
			"98:1:F|F|*>F|*", // FADD
			"99:1:D|D|*>D|*", // DADD
			"100:1:I|I|*>I|*", // ISUB
			"101:1:J|J|*>J|*", // LSUB
			"102:1:F|F|*>F|*", // FSUB
			"103:1:D|D|*>D|*", // DSUB
			"104:1:I|I|*>I|*", // IMUL
			"105:1:J|J|*>J|*", // LMUL
			"106:1:F|F|*>F|*", // FMUL
			"107:1:D|D|*>D|*", // DMUL
			"108:1:I|I|*>I|*", // IDIV
			"109:1:J|J|*>J|*", // LDIV
			"110:1:F|F|*>F|*", // FDIV
			"111:1:D|D|*>D|*", // DDIV
			"112:1:I|I|*>I|*", // IREM
			"113:1:J|J|*>J|*", // LREM
			"114:1:F|F|*>F|*", // FREM
			"115:1:D|D|*>D|*", // DREM
			"120:1:I|I|*>I|*", // ISHL
			"121:1:I|J|*>J|*", // LSHL
			"122:1:I|I|*>I|*", // ISHR
			"123:1:I|J|*>J|*", // LSHR
			"124:1:I|I|*>I|*", // IUSHR
			"125:1:I|J|*>J|*", // LUSHR
			"126:1:I|I|*>I|*", // IAND
			"127:1:J|J|*>J|*", // LAND
			"128:1:I|I|*>I|*", // IOR
			"129:1:J|J|*>J|*", // LOR
			"130:1:I|I|*>I|*", // IXOR
			"131:1:J|J|*>J|*", // LXOR
			"116:1:I|*>I|*", // INEG
			"117:1:J|*>J|*", // LNEG
			"118:1:F|*>F|*", // FNEG
			"119:1:D|*>D|*", // DNEG

			"132:1:*>*", // IINC
			
			// 133 - 147
			"133:1:I|*>J|*", // I2L
			"134:1:I|*>F|*", // I2F
			"135:1:I|*>D|*", // I2D
			"136:1:J|*>I|*", // L2I
			"137:1:J|*>F|*", // L2F
			"138:1:J|*>D|*", // L2D
			"139:1:F|*>I|*", // F2I
			"140:1:F|*>J|*", // F2L
			"141:1:F|*>D|*", // F2D
			"142:1:D|*>I|*", // D2I
			"143:1:D|*>J|*", // D2L
			"144:1:D|*>F|*", // D2F
			"145:1:I|*>B|*", // I2B
			"146:1:I|*>C|*", // I2C
			"147:1:I|*>S|*", // I2S
			
			// 148 - 158
			"148:1:J|J|*>I|*", // LCMP
			"149:1:F|F|*>I|*", // FCMPL
			"150:1:F|F|*>I|*", // FCMPG
			"151:1:D|D|*>I|*", // DCMPL
			"152:1:D|D|*>I|*", // DCMPG
			"153:1:I|*>*", // IFEQ
			"154:1:I|*>*", // IFNE
			"155:1:I|*>*", // IFLT
			"156:1:I|*>*", // IFGE
			"157:1:I|*>*", // IFGT
			"158:1:I|*>*", // IFLE
			
			"159:1:I|I|*>*", // IF_ICMPEQ
			"160:1:I|I|*>*", // IF_ICMPNE
			"161:1:I|I|*>*", // IF_ICMPLT
			"162:1:I|I|*>*", // IF_ICMPGE
			"163:1:I|I|*>*", // IF_ICMPGT
			"164:1:I|I|*>*", // IF_ICMPLE
			"165:1:X|X|*>*", // IF_ACMPEQ
			"166:1:X|X|*>*", // IF_ACMPNE
			
			"167:0", // GOTO
			// JSR and RET omitted (unsupported)
			"170:1:I|*>*", // TABLESWITCH
			"171:1:I|*>*", // LOOKUPSWITCH
			
			"172:1:I|*>", // IRETURN
			"173:1:J|*>", // LRETURN
			"174:1:F|*>", // FRETURN
			"175:1:D|*>", // DRETURN
			"176:1:X|*>", // ARETURN
			"177:1:>", // RETURN
			
			"178:1:*>X|*", // GETSTATIC
			"179:1:X|*>*", // PUTSTATIC
			"180:1:O|*>X|*", // GETFIELD
			"181:1:X|O|*>*", // PUTFIELD
			
			// TODO: add dynamic getters for
			//       variable length insns.
	};
	
	/* public static void main(String[] args) {
		// 96 IADD - 115 DREM (2)
		// 116-119 I,L,F,D NEG (1)
		// 120-131 shift ops (2)

		// N.B: These convert long to L instead of J.
		gen_arith_configs(96, 115, 2);
		gen_arith_configs(120, 131, 2);
		gen_arith_configs(116, 119, 1);
		gen_cast_configs(133, 147);
		gen_cmp_configs(148, 158);
	}
	private static void gen_arith_configs(int f, int l, int s) {
		for(int i=f; i <= l; i++) {
			String n = Printer.OPCODES[i];
			char t = Character.toUpperCase(n.charAt(0));
			
			String pre = "";
			for(int j=0; j < s; j++) {
				pre += t;
				pre += "|";
			}
			pre += "*";
			
			String post = t + "|*";
			
			String p = String.format("\"%d:1:%s>%s\", // %s", i, pre, post, n);
			System.out.println(p);;
		}
	}
	private static void gen_cast_configs(int f, int l) {
		for(int i=f; i <= l; i++) {
			String n = Printer.OPCODES[i];
			char ft = Character.toUpperCase(n.charAt(0));
			char tt = Character.toUpperCase(n.charAt(2));
			
			String pre = ft + "|*";
			String post = tt + "|*";
			
			String p = String.format("\"%d:1:%s>%s\", // %s", i, pre, post, n);
			System.out.println(p);
		}
	}
	private static void gen_cmp_configs(int f, int l) {
		for(int i=f; i <= l; i++) {
			String n = Printer.OPCODES[i];
			char t = Character.toUpperCase(n.charAt(0));
			
			String pre = t + "";
			if(n.contains("CMP")) {
				pre += "|" + t;
			}
			pre += "|*";
			
			String post = "I|*";
			
			String p = String.format("\"%d:1:%s>%s\", // %s", i, pre, post, n);
			System.out.println(p);
		}
	} */
	
	static class VerifierToken {
		static final Map<String, VerifierToken> cache = new HashMap<>();
		
		static final int CLASS_EMPTY = 0;
		static final int CLASS_WORD_LEN = 1;
		static final int CLASS_NULL = 2;
		static final int CLASS_ANY_SINGLE = 3;
		static final int CLASS_TYPE_SUB = 4;
		static final int CLASS_TYPE_EXACT = 5;
		static final int CLASS_ALL = 6;
		
		final Object tok;
		final int dims;
		final int tclass;
		
		VerifierToken(String t) {
			Object tok = t;
			int dims = 0;
			int tclass = -1;
			
			int len = t.length();
			if(len == 0) {
				tclass = CLASS_EMPTY;
			} else {
				String actualType = t;
				String arrayClean = t.replace("[", "");
				dims = t.length() - arrayClean.length();
				char c = arrayClean.charAt(0);
				
				boolean exact = (c == '#');
				if(exact) {
					// exact
					c = arrayClean.charAt(1);
					actualType = actualType.substring(1);
				}
				
				switch(c) {
					case '1':
					case '2':
						tok = Integer.parseInt(t);
						tclass = CLASS_WORD_LEN;
						break;
					case 'N':
						tclass = CLASS_NULL;
						break;
					case 'X':
						tclass = CLASS_ANY_SINGLE;
						break;
					case 'C':
					case 'B':
					case 'S':
					case 'I':
					case 'F':
					case 'D':
					case 'J':
					case 'Z':
						tok = Type.getType(actualType);
						tclass = CLASS_TYPE_EXACT;
						break;
					case '*':
						tclass = CLASS_ALL;
						break;
				}
				
				if(tclass == -1) {
					tok = Type.getType(actualType);
					tclass = exact ? CLASS_TYPE_EXACT : CLASS_TYPE_SUB;
				}
			
				if(tclass == -1) {
					throw new UnsupportedOperationException(t);
				}
			}

			this.tok = tok;
			this.dims = dims;
			this.tclass = tclass;
		}
		
		boolean matches(Expr e) {
			Type type = e.getType();
			
			if(tclass == CLASS_EMPTY) {
				// We don't add these to the token list
				// so it shouldn't appear here.
				throw new UnsupportedOperationException();
			} else if(tclass == CLASS_WORD_LEN) {
				int s = size(type);
				return s == (int)tok;
			} else if(tclass == CLASS_NULL) {
				if(e.getOpcode() == Opcode.CONST_LOAD) {
					ConstantExpr c = (ConstantExpr) e;
					return c.getConstant() == null;
				} else {
					return false;
				}
			} else if(tclass == CLASS_ANY_SINGLE) {
				return true;
			} else if(tclass == CLASS_ALL) {
				// the matcher omits this token.
				throw new UnsupportedOperationException();
			} else if(tclass == CLASS_TYPE_EXACT) {
				return type.equals(tok);
			} else if(tclass == CLASS_TYPE_SUB) {
				if(dims > 0) {
					String arrayObjType = type.getInternalName();
					arrayObjType = arrayObjType.replace("[", "");
					
					int tdims = type.getInternalName().length() - arrayObjType.length();
					
					if(tdims != dims) {
						return false;
					} else {
						// String tokObjType = ((Type) tok).getInternalName().substring(dims);
						// if(tokObjType.eq)
						
						// TODO: check if arrayObjType extends tokObjType.
						return true;
					}
				} else {
					return type.equals(tok);
				}
			} else {
				throw new UnsupportedOperationException(e + ",  " + type + ",  " + tclass + ",   " + tok + ",   " + dims);
			}
		}
		
		static int size(Type t) {
			String s = t.toString();
			switch(s) {
				case "D":
				case "J":
					return 2;
				default:
					return 1;
			}
		}
		
		static VerifierToken[] makeTokens(String stack) {
			if(stack.isEmpty()) {
				return new VerifierToken[0];
			} else {
				String[] ss = stack.split("\\|");
				List<VerifierToken> lst = new ArrayList<>();
				
				for(int i=0; i < ss.length; i++) {
					String s = ss[i];
					
					VerifierToken t = null;
					if(cache.containsKey(s)) {
						t = cache.get(s);
					} else {
						t = new VerifierToken(s);
						cache.put(s, t);
					}
					
					if(t.tclass != VerifierToken.CLASS_EMPTY) {
						lst.add(t);
					}
				}
				
				return lst.toArray(new VerifierToken[ss.length]);
			}
		}
	}
	
	static class VerifierRule {
		final VerifierToken[] preTokens;
		final VerifierToken[] postTokens;
		
		VerifierRule(VerifierToken[] preTokens, VerifierToken[] postTokens) {
			this.preTokens = preTokens;
			this.postTokens = postTokens;
		}

		boolean match_attempt(ExpressionStack stack, boolean pre) {
			if(pre) {
				return match_attempt(stack, preTokens);
			} else {
				return match_attempt(stack, postTokens);
			}
		}
		
		boolean match_attempt(ExpressionStack stack, VerifierToken[] tokens) {
			if(tokens.length == 0) {
				if(stack.size() != 0) {
					return false;
				}
			} else {
				VerifierToken last = tokens[tokens.length - 1];
				int expectedSize = tokens.length;
				/* Since the all(*) matcher can only appear at the
				 * end of the token sequence, if we have one, we
				 * only check the first n-1 exprs of the stack where
				 * n is the number of elements on the stack as the last
				 * one will match 0 or more elements regardless.*/
				if(last.tclass == VerifierToken.CLASS_ALL/* '*' */) {
					expectedSize = expectedSize - 1;
				}
				
				if(expectedSize > 0) {
					for(int i=0; i < expectedSize; i++) {
						VerifierToken t = tokens[i];

						Expr e = null;

						if(i >= stack.size()) {
							return false;
							// throw new IllegalStateException(String.format("Stack:%s, tokLen:%d, expSize:%d, sSize:%d, sHeight:%d, i:%d", stack, tokens.length, expectedSize, stack.size(), stack.height(), i));
						} else {
							e = stack.peek(i);
						}
						if(!t.matches(e)) {
							return false;
						}
					}
				}
			}
			
			return true;
		}
	}
	
	private static void compile_configs(String[] configs) {
		for(String c : configs) {
			String[] ps = c.split(":");
			
			int op = Integer.parseInt(ps[0]);
			int num_confs = Integer.parseInt(ps[1]);
			
			if(ps.length != (2 + num_confs) /*header fields + confs*/) {
				throw new UnsupportedOperationException("Cannot parse config: " + c);
			}
			
			List<VerifierRule> compiledConfs = new ArrayList<>();
			
			for(int i=0; i < num_confs; i++) {
				String conf = ps[2 + i];
				
				String[] cfs = conf.split(">");
				
				String pre, post;
				if(cfs.length == 0) {
					pre = "";
					post = "";
				} else if(cfs.length == 1) {
					// decide which side is empty
					if(conf.charAt(0) == '>') {
						// first is empty
						pre = "";
						post = cfs[0];
					} else {
						// second is empty
						pre = cfs[0];
						post = "";
					}
				} else if(cfs.length == 2) {
					pre = cfs[0];
					post = cfs[1];
				} else {
					throw new UnsupportedOperationException("Malformed config @" + i + ":: " + c);
				}

				try {
					VerifierToken[] preTokens = VerifierToken.makeTokens(pre);
					VerifierToken[] postTokens = VerifierToken.makeTokens(post);
					
					VerifierRule rule = new VerifierRule(preTokens, postTokens);
					compiledConfs.add(rule);
				} catch(Exception e) {
					throw new UnsupportedOperationException("Malformed config: " + conf + " in " + c, e);
				}
			}
			
			vrules.put(op, compiledConfs);
			__vrules.put(op, Printer.OPCODES[op] + "/" + c);
		}
	}
	
	private static List<VerifierRule> find_verify_matches(int bIndex, int op, ExpressionStack stack) {
		List<VerifierRule> rules = vrules.get(op);
		
		if(rules == null) {
			System.err.println("Cannot verify " + Printer.OPCODES[op] + " (no rules).  Stack: " + stack);
			return null;
		}
		
		List<VerifierRule> possible = new ArrayList<>();
		
		for(VerifierRule r : rules) {
			if(r.match_attempt(stack, true)) {
				possible.add(r);
			}
		}
		
		if(possible.isEmpty() && !rules.isEmpty()) {
			throw new IllegalStateException("Pre stack: " + stack + ", configs: " + __vrules.get(op) + " @" + bIndex);
		}
		
		return possible;
	}
	
	private static void confirm_rules(int bIndex, int opcode, List<VerifierRule> rules, ExpressionStack stack) {
		List<VerifierRule> vr = vrules.get(opcode);
		
		if(vr.size() > 0) {
			for(VerifierRule r : rules) {
				if(r.match_attempt(stack, false)) {
					return;
				}
			}
			
			throw new IllegalStateException("Post stack: " + stack + ", configs: " + __vrules.get(opcode) + " @" + bIndex);
		}
	}

	private static final Map<Integer, String> __vrules;
	private static final Map<Integer, List<VerifierRule>> vrules;
	
	static {
		__vrules = new HashMap<>();
		vrules = new HashMap<>();
		compile_configs(STACK_CONFIGS);
	}
	
	private static final int[] EMPTY_STACK_HEIGHTS = new int[]{};
	private static final int[] SINGLE_RETURN_HEIGHTS = new int[]{1};
	private static final int[] DOUBLE_RETURN_HEIGHTS = new int[]{2};
	
	private static final int[] DUP_HEIGHTS = new int[]{1};
	private static final int[] SWAP_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP_X1_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP2_32_HEIGHTS = new int[]{1, 1};
	private static final int[] DUP2_X1_32_HEIGHTS = new int[]{1, 1, 1};
	private static final int[] DUP2_X1_64_HEIGHTS = new int[]{2, 1};
	private static final int[] DUP2_X2_64x64_HEIGHTS = new int[]{2, 2};
	private static final int[] DUP2_X2_64x32_HEIGHTS = new int[]{2, 1, 1};
	private static final int[] DUP2_X2_32x64_HEIGHTS = new int[]{1, 1, 2};
	private static final int[] DUP2_X2_32x32_HEIGHTS = new int[]{1, 1, 1, 1};
	private static final int[] DUP_X2_64_HEIGHTS = new int[]{1, 2};
	private static final int[] DUP_X2_32_HEIGHTS = new int[]{1, 1, 1};

	protected final InsnList insns;
	private final BitSet finished;
	private final LinkedList<LabelNode> queue;
	private final Set<LabelNode> marks;
	
	private BitSet stacks;
	protected BasicBlock currentBlock;
	protected ExpressionStack currentStack;
	protected boolean saved;

	public GenerationPass(ControlFlowGraphBuilder builder) {
		super(builder);
		
		/* a block can exist in the map in the graph 
		 * but not be populated yet.
		 * we do this so that when a flow function is reached, 
		 * we can create the block reference and then handle
		 * the creation mechanism later. */
		finished = new BitSet();
		queue = new LinkedList<>();
		stacks = new BitSet();
		marks = new HashSet<>();
		
		insns = builder.method.instructions;
	}
	
	protected BasicBlock makeBlock(LabelNode label) {
		BasicBlock b = new BasicBlock(builder.graph, ++builder.count, label);
		queue(label);
		builder.graph.addVertex(b);
		return b;
	}
	
	protected BasicBlock resolveTarget(LabelNode label) {
		BasicBlock block = builder.graph.getBlock(label);
		if(block == null) {
			block = makeBlock(label);
		}
		return block;
	}
	
	private void init() {
		entry(checkLabel());
		
		for(TryCatchBlockNode tc : builder.method.tryCatchBlocks) {
			handler(tc);
		}
	}

	private LabelNode checkLabel() {
		AbstractInsnNode first = insns.getFirst();
		if (first == null) {
			LabelNode nFirst = new LabelNode();
			insns.add(nFirst);
			first = nFirst;
		} else if (!(first instanceof LabelNode)) {
			LabelNode nFirst = new LabelNode();
			insns.insertBefore(first, nFirst);
			first = nFirst;
		}
		return (LabelNode) first;
	}
	
	protected void entry(LabelNode firstLabel) {
		LabelNode l = new LabelNode();
		BasicBlock entry = new BasicBlock(builder.graph, ++builder.count, l);
		entry.setFlag(BasicBlock.FLAG_NO_MERGE, true);
		
		builder.graph.addVertex(entry);
		builder.graph.getEntries().add(entry);
		entry.setInputStack(new ExpressionStack(16));
		defineInputs(builder.method, entry);
		insns.insertBefore(firstLabel, l);
		
		BasicBlock b = makeBlock(firstLabel);
		b.setInputStack(new ExpressionStack(16));
		queue(firstLabel);
		
		builder.graph.addEdge(entry, new ImmediateEdge<>(entry, b));
	}
	
	protected void handler(TryCatchBlockNode tc) {
		LabelNode label = tc.handler;
		BasicBlock handler = resolveTarget(label);
		marks.add(tc.start);
		marks.add(tc.end);
		if(handler.getInputStack() != null) {
//			System.err.println(handler.getInputStack());
//			System.err.println("Double handler: " + handler.getId() + " " + tc);
			return;
		}
		
		ExpressionStack stack = new ExpressionStack(16);
		handler.setInputStack(stack);
		
		CaughtExceptionExpr expr = new CaughtExceptionExpr(tc.type);
		Type type = expr.getType();
		VarExpr var = _var_expr(0, type, true);
		CopyVarStmt stmt = copy(var, expr, handler);
		handler.add(stmt);
		
		stack.push(load_stack(0, type));
		
		queue(label);
		
		stacks.set(handler.getNumericId());
	}
	
	protected void defineInputs(MethodNode m, BasicBlock b) {
		Type[] args = Type.getArgumentTypes(m.desc);
		int index = 0;
		if((m.access & Opcodes.ACC_STATIC) == 0) {
			addEntry(index, Type.getType("L" + m.owner.name + ";"), b);
			index++;
		}
	
		for(int i=0; i < args.length; i++) {
			Type arg = args[i];
			addEntry(index, arg, b);
			index += arg.getSize();
		}
	}
	
	private void addEntry(int index, Type type, BasicBlock b) {
		VarExpr var = _var_expr(index, type, false);
		CopyVarStmt stmt = selfDefine(var);
		builder.assigns.getNonNull(var.getLocal()).add(b);
		b.add(stmt);
	}
	
	protected CopyVarStmt selfDefine(VarExpr var) {
		return new CopyVarStmt(var, var, true);
	}
	
	protected void queue(LabelNode label) {
		if(!queue.contains(label)) {
			queue.addLast(label);
		}
	}
	
	protected void preprocess(BasicBlock b) {
		ExpressionStack stack = b.getInputStack().copy();
		stacks.set(b.getNumericId());
		
		currentBlock = b;
		currentStack = stack;
		saved = false;
	}
	
	protected void process(LabelNode label) {
		/* it may not be properly initialised yet, however. */
		BasicBlock block = builder.graph.getBlock(label);
		
		/* if it is, we don't need to process it. */
		if(block != null && finished.get(block.getNumericId())) {
			return;
		} else if(block == null) {
			block = makeBlock(label);
		} else {
			// i.e. not finished.
		}
		
		preprocess(block);
		
		/* populate instructions. */
		int codeIndex = insns.indexOf(label);
		finished.set(block.getNumericId());
		while(codeIndex < insns.size() - 1) {
			AbstractInsnNode ain = insns.get(++codeIndex);
			int type = ain.type();
			
			if(ain.opcode() != -1) {
				process(block, ain);
			}
			
			if(type == LABEL) {
				// split into new block
				BasicBlock immediate = resolveTarget((LabelNode) ain);
				builder.graph.addEdge(block, new ImmediateEdge<>(block, immediate));
				break;
			} else if(type == JUMP_INSN) {
				JumpInsnNode jin = (JumpInsnNode) ain;
				BasicBlock target = resolveTarget(jin.label);
				
				if(jin.opcode() == JSR) {
					throw new UnsupportedOperationException("jsr " + builder.method);
				} else if(jin.opcode() == GOTO) {
					builder.graph.addEdge(block, new UnconditionalJumpEdge<>(block, target, jin.opcode()));
				} else {
					builder.graph.addEdge(block, new ConditionalJumpEdge<>(block, target, jin.opcode()));
					int nextIndex = codeIndex + 1;
					AbstractInsnNode nextInsn = insns.get(nextIndex);
					if(!(nextInsn instanceof LabelNode)) {
						LabelNode newLabel = new LabelNode();
						insns.insert(ain, newLabel);
						nextInsn = newLabel;
					}
					
					// create immediate successor reference if it's not already done
					BasicBlock immediate = resolveTarget((LabelNode) nextInsn);
					builder.graph.addEdge(block, new ImmediateEdge<>(block, immediate));
				}
				break;
			} else if(type == LOOKUPSWITCH_INSN) {
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				
				for(int i=0; i < lsin.keys.size(); i++) {
					BasicBlock target = resolveTarget(lsin.labels.get(i));
					builder.graph.addEdge(block, new SwitchEdge<>(block, target, lsin, lsin.keys.get(i)));
				}
				
				BasicBlock dflt = resolveTarget(lsin.dflt);
				builder.graph.addEdge(block, new DefaultSwitchEdge<>(block, dflt, lsin));
				break;
			} else if(type == TABLESWITCH_INSN) {
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				for(int i=tsin.min; i <= tsin.max; i++) {
					BasicBlock target = resolveTarget(tsin.labels.get(i - tsin.min));
					builder.graph.addEdge(block, new SwitchEdge<>(block, target, tsin, i));
				}
				BasicBlock dflt = resolveTarget(tsin.dflt);
				builder.graph.addEdge(block, new DefaultSwitchEdge<>(block, dflt, tsin));
				break;
			} else if(isExitOpcode(ain.opcode())) {
				break;
			}
		}
		
		// TODO: check if it should have an immediate.
		BasicBlock im = block.getImmediate();
		if (im != null && !queue.contains(im)) {
			// System.out.println("Updating " + block.getId() + " -> " + im.getId());
			// System.out.println("  Pre: " + currentStack);
			update_target_stack(block, im, currentStack);
			// System.out.println("  Pos: " + currentStack);
		}
	}
	
	static boolean isExitOpcode(int opcode) {
		switch(opcode) {
			case Opcodes.RET:
			case Opcodes.ATHROW:
			case Opcodes.RETURN:
			case Opcodes.IRETURN:
			case Opcodes.LRETURN:
			case Opcodes.FRETURN:
			case Opcodes.DRETURN:
			case Opcodes.ARETURN: {
				return true;
			}
			default: {
				return false;
			}
		}
	}
	
	protected void process(BasicBlock b, AbstractInsnNode ain) {
		int opcode = ain.opcode();
		
		// System.out.println("Executing " + Printer.OPCODES[opcode]);
		// System.out.println(" PreStack: " + currentStack);
		
		List<VerifierRule> possibleRules = null;
		
		if(VERIFY) {
			possibleRules = find_verify_matches(insns.indexOf(ain), opcode, currentStack);
		}
		
		switch (opcode) {
			case -1: {
				if (ain instanceof LabelNode)
					throw new IllegalStateException("Block should not contain label.");
				break;
			}
			case BIPUSH:
			case SIPUSH:
				_const(((IntInsnNode) ain).operand);
				break;
			case ACONST_NULL:
				_const(null);
				break;
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
				_const(opcode - ICONST_M1 - 1);
				break;
			case LCONST_0:
			case LCONST_1:
				_const((long) (opcode - LCONST_0));
				break;
			case FCONST_0:
			case FCONST_1:
			case FCONST_2:
				_const((float) (opcode - FCONST_0));
				break;
			case DCONST_0:
			case DCONST_1:
				_const((long) (opcode - DCONST_0));
				break;
			case LDC:
				_const(((LdcInsnNode) ain).cst);
				break;
			case LCMP:
			case FCMPL:
			case FCMPG:
			case DCMPL:
			case DCMPG: {
				_compare(ValueComparisonType.resolve(opcode));
				break;
			}
			case NEWARRAY: {
				save_stack(false);
				_new_array(
					new Expr[] { pop() }, 
					TypeUtils.getPrimitiveArrayType(((IntInsnNode) ain).operand)
				);
				break;
			}
			case ANEWARRAY: {
				save_stack(false);
				String typeName = ((TypeInsnNode) ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "[L" + typeName + ";";
				else
					typeName = '[' + typeName;
				_new_array(
					new Expr[] { pop() }, 
					Type.getType(typeName)
				);
				break;
			}
			case MULTIANEWARRAY: {
				save_stack(false);
				MultiANewArrayInsnNode in = (MultiANewArrayInsnNode) ain;
				Expr[] bounds = new Expr[in.dims];
				for (int i = in.dims - 1; i >= 0; i--) {
					bounds[i] = pop();
				}
				_new_array(bounds, Type.getType(in.desc));
				break;
			}

			case RETURN:
				_return(Type.VOID_TYPE);
				break;
			case ATHROW:
				_throw();
				break;
				
			case MONITORENTER:
				_monitor(MonitorMode.ENTER);
				break;
			case MONITOREXIT:
				_monitor(MonitorMode.EXIT);
				break;
				
			case IRETURN:
			case LRETURN:
			case FRETURN:
			case DRETURN:
			case ARETURN:
				_return(Type.getReturnType(builder.method.desc));
				break;
			case IADD:
			case LADD:
			case FADD:
			case DADD:
			case ISUB:
			case LSUB:
			case FSUB:
			case DSUB:
			case IMUL:
			case LMUL:
			case FMUL:
			case DMUL:
			case IDIV:
			case LDIV:
			case FDIV:
			case DDIV:
			case IREM:
			case LREM:
			case FREM:
			case DREM:
			
			case ISHL:
			case LSHL:
			case ISHR:
			case LSHR:
			case IUSHR:
			case LUSHR:
			
			case IAND:
			case LAND:
				
			case IOR:
			case LOR:
				
			case IXOR:
			case LXOR:
				_arithmetic(Operator.resolve(opcode));
				break;
			
			case INEG:
			case DNEG:
				_neg();
				break;
				
			case ARRAYLENGTH:
				_arraylength();
				break;
				
			case IALOAD:
			case LALOAD:
			case FALOAD:
			case DALOAD:
			case AALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
				_load_array(ArrayType.resolve(opcode));
				break;
				
			case IASTORE:
			case LASTORE:
			case FASTORE:
			case DASTORE:
			case AASTORE:
			case BASTORE:
			case CASTORE:
			case SASTORE:
				_store_array(ArrayType.resolve(opcode));
				break;
				
			case POP:
				_pop(1);
				break;
			case POP2:
				_pop(2);
				break;
				
			case DUP:
				_dup();
				break;
			case DUP_X1:
				_dup_x1();
				break;
			case DUP_X2:
				_dup_x2();
				break;

			case DUP2:
				_dup2();
				break;
			case DUP2_X1:
				_dup2_x1();
				break;
			case DUP2_X2:
				_dup2_x2();
				break;
				
			case SWAP:
				_swap();
				break;
				
			case I2L:
			case I2F:
			case I2D:
			case L2I:
			case L2F:
			case L2D:
			case F2I:
			case F2L:
			case F2D:
			case D2I:
			case D2L:
			case D2F:
			case I2B:
			case I2C:
			case I2S:
				_cast(TypeUtils.getCastType(opcode));
				break;
			case CHECKCAST:
				String typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[') // arrays aren't objects.
					typeName = "L" + typeName + ";";
				_cast(Type.getType(typeName));
				break;
			case INSTANCEOF:
				typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "L" + typeName + ";";
				_instanceof(Type.getType(typeName));
				break;
			case NEW:
				typeName = ((TypeInsnNode)ain).desc;
				if (typeName.charAt(0) != '[')
					typeName = "L" + typeName + ";";
				_new(Type.getType(typeName));
				break;
				
			case INVOKEDYNAMIC:
				InvokeDynamicInsnNode dy = (InvokeDynamicInsnNode) ain;
				_dynamic_call(dy.bsm, dy.bsmArgs, dy.name, dy.desc);
				break;
			case INVOKEVIRTUAL:
			case INVOKESTATIC:
			case INVOKESPECIAL:
			case INVOKEINTERFACE:
				MethodInsnNode min = (MethodInsnNode) ain;
				_call(opcode, min.owner, min.name, min.desc);
				break;
				
			case ILOAD:
			case LLOAD:
			case FLOAD:
			case DLOAD:
			case ALOAD:
				_load(((VarInsnNode) ain).var, TypeUtils.getLoadType(opcode));
				break;
				
			case ISTORE:
			case LSTORE:
			case FSTORE:
			case DSTORE:
			case ASTORE:
				_store(((VarInsnNode) ain).var, TypeUtils.getStoreType(opcode));
				break;
				
			case IINC:
				IincInsnNode iinc = (IincInsnNode) ain;
				_inc(iinc.var, iinc.incr);
				break;
				
			case PUTFIELD:
			case PUTSTATIC: {
				FieldInsnNode fin = (FieldInsnNode) ain;
				_store_field(opcode, fin.owner, fin.name, fin.desc);
				break;
			}
			case GETFIELD:
			case GETSTATIC:
				FieldInsnNode fin = (FieldInsnNode) ain;
				_load_field(opcode, fin.owner, fin.name, fin.desc);
				break;
				
			case TABLESWITCH: {
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				LinkedHashMap<Integer, BasicBlock> targets = new LinkedHashMap<>();
				for(int i=tsin.min; i <= tsin.max; i++) {
					BasicBlock targ = resolveTarget(tsin.labels.get(i - tsin.min));
					targets.put(i, targ);
				}
				_switch(targets, resolveTarget(tsin.dflt));
				break;
			}
			
			case LOOKUPSWITCH: {
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				LinkedHashMap<Integer, BasicBlock> targets = new LinkedHashMap<>();
				for(int i=0; i < lsin.keys.size(); i++) {
					int key = lsin.keys.get(i);
					BasicBlock targ = resolveTarget(lsin.labels.get(i));
					targets.put(key, targ);
				}
				_switch(targets, resolveTarget(lsin.dflt));
				break;
			}
			
			case GOTO:
				_jump_uncond(resolveTarget(((JumpInsnNode) ain).label));
				break;
			case IFNULL:
			case IFNONNULL:
				_jump_null(resolveTarget(((JumpInsnNode) ain).label), opcode == IFNONNULL);
				break;
				
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
				_jump_cmp(resolveTarget(((JumpInsnNode) ain).label), ComparisonType.getType(opcode));
				break;
				
			case IFEQ:
			case IFNE:
			case IFLT:
			case IFGE:
			case IFGT:
			case IFLE:
				_jump_cmp0(resolveTarget(((JumpInsnNode) ain).label), ComparisonType.getType(opcode));
				break;
		}
		
		if(VERIFY) {
			if(possibleRules != null) {
				confirm_rules(insns.indexOf(ain), opcode, possibleRules, currentStack);
			}
		}
		

		// System.out.println(" PosStack: " + currentStack);
	}
	
	protected void _nop() {

	}

	protected void _const(Object o) {
		Expr e = new ConstantExpr(o);
		// int index = currentStack.height();
		// Type type = assign_stack(index, e);
		// push(load_stack(index, type));
		push(e);
	}

	protected void _compare(ValueComparisonType ctype) {
		save_stack(false);
		Expr right = pop();
		Expr left = pop();
		push(new ComparisonExpr(left, right, ctype));
	}

	protected void _return(Type type) {
		if (type == Type.VOID_TYPE) {
			currentStack.assertHeights(EMPTY_STACK_HEIGHTS);
			addStmt(new ReturnStmt());
		} else {
			save_stack(false);
			if(type.getSize() == 2) {
				currentStack.assertHeights(DOUBLE_RETURN_HEIGHTS);
			} else {
				currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
			}
			addStmt(new ReturnStmt(type, pop()));
		}
	}

	protected void _throw() {
		save_stack(false);
		currentStack.assertHeights(SINGLE_RETURN_HEIGHTS);
		addStmt(new ThrowStmt(pop()));
	}

	protected void _monitor(MonitorMode mode) {
		save_stack(false);
		addStmt(new MonitorStmt(pop(), mode));
	}

	protected void _arithmetic(Operator op) {
		save_stack(false);
		Expr e = new ArithmeticExpr(pop(), pop(), op);
		int index = currentStack.height();
		Type type = assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _neg() {
		save_stack(false);
		push(new NegationExpr(pop()));
	}
	
	protected void _arraylength() {
		save_stack(false);
		push(new ArrayLengthExpr(pop()));
	}
	
	protected void _load_array(ArrayType type) {
		save_stack(false);
		// prestack: var1, var0 (height = 2)
		// poststack: var0
		// assignments: var0 = var0[var1]
//		int height = currentStack.height();
		Expr index = pop();
		Expr array = pop();
		push(new ArrayLoadExpr(array, index, type));
//		assign_stack(height - 2, new ArrayLoadExpr(array, index, type));
//		push(load_stack(height - 2, type.getType()));
	}
	
	protected void _store_array(ArrayType type) {
		save_stack(false);
		Expr value = pop();
		Expr index = pop();
		Expr array = pop();
		addStmt(new ArrayStoreStmt(array, index, value, type));
	}
	
	protected void _pop(int amt) {
		for(int i=0; i < amt; ) {
			Expr top = pop();
			addStmt(new PopStmt(top));
			i += top.getType().getSize();
		}
	}
	
	protected void _dup() {
		// prestack: var0 (height = 1)
		// poststack: var1, var0
		// assignments: var1 = var0(initial)
		currentStack.assertHeights(DUP_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);
		
		Expr var0 = pop();

		Type var1Type = assign_stack(baseHeight, var0); // var1 = var0
		push(load_stack(baseHeight - 1, var0.getType())); //  push var0
		push(load_stack(baseHeight, var1Type)); // push var1
	}

	protected void _dup_x1() {
		// prestack: var1, var0 (height = 2)
		// poststack: var2, var1, var0
		// assignments: var0 = var1(initial)
		// assignments: var1 = var0(initial)
		// assignments: var2 = var1(initial)
		currentStack.assertHeights(DUP_X1_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);

		Expr var1 = pop();
		Expr var0 = pop();

		Type var3Type = assign_stack(baseHeight + 1, var0); // var3 = var0

		Type var0Type = assign_stack(baseHeight - 2, var1); // var0 = var1(initial)
		Type var2Type = assign_stack(baseHeight + 0, var1.copy()); // var2 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 1, var3Type)); // var1 = var3 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
		push(load_stack(baseHeight + 0, var2Type)); // push var2
	}

	protected void _dup_x2() {
		int baseHeight = currentStack.height();
		save_stack(false);

		if(currentStack.peek(1).getType().getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var1, var0
			// assignments: var0 = var2(initial)
			// assignments: var1 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP_X2_64_HEIGHTS);

			Expr var2 = pop();
			Expr var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)

			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var2); // var3 = var2(initial)
			Type var1Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var4Type)); // var1 = var4 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight + 0, var3Type)); // push var3
		} else {
			// prestack: var2, var1, var0 (height = 3)
			// poststack: var3, var2, var1, var0
			// assignments: var0 = var2(initial)
			// assignments: var1 = var0(initial)
			// assignments: var2 = var1(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP_X2_32_HEIGHTS);

			Expr var2 = pop();
			Expr var1 = pop();
			Expr var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)
			Type var5Type = assign_stack(baseHeight + 2, var1); // var5 = var1(initial)

			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var2.copy()); // var3 = var2(initial)
			Type var1Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var4Type)); // var1 = var4 = var0(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 2, var5Type)); // var2 = var5 = var1(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight + 0, var3Type)); // push var3
		}
	}

	protected void _dup2() {
		int baseHeight = currentStack.height();
		save_stack(false);

		if(peek().getType().getSize() == 2) {
			// prestack: var0 (height = 2)
			// poststack: var2, var0
			// assignments: var2 = var0

			Expr var0 = pop();

			Type var2Type = assign_stack(baseHeight, var0); // var2 = var0
			push(load_stack(baseHeight - 2, var0.getType())); //  push var0
			push(load_stack(baseHeight, var2Type)); // push var2
		} else {
			// prestack: var1, var0 (height = 2)
			// poststack: var3, var2, var1, var0
			// assignments: var2 = var0(initial)
			// assignments: var3 = var1(initial)
			currentStack.assertHeights(DUP2_32_HEIGHTS);

			Expr var1 = pop();
			Expr var0 = pop();

			Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
			Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

			push(load_stack(baseHeight - 2, var0.getType())); // push var0
			push(load_stack(baseHeight - 1, var1.getType())); // push var1
			push(load_stack(baseHeight + 0, var2Type)); // push var2
			push(load_stack(baseHeight + 1, var3Type)); // push var3
		}
	}

	protected void _dup2_x1() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();
		save_stack(false);

		if(topType.getSize() == 2) {
			// prestack: var2, var0 (height = 3)
			// poststack: var3, var2, var0
			// assignments: var0 = var2(initial)
			// assignemnts: var2 = var0(initial)
			// assignments: var3 = var2(initial)
			currentStack.assertHeights(DUP2_X1_64_HEIGHTS);

			Expr var2 = pop();
			Expr var0 = pop();

			Type var4Type = assign_stack(baseHeight + 1, var0); // var4 = var0(initial)

			Type var3Type = assign_stack(baseHeight - 0, var2); // var3 = var2(initial)
			Type var0Type = assign_stack(baseHeight - 3, var2); // var0 = var2(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 1, var4Type)); // var2 = var4 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight - 0, var3Type)); // push var3
		} else {
			// prestack: var2, var1, var0 (height = 3)
			// poststack: var4, var3, var2, var1, var0
			// assignments: var0 = var1(initial)
			// assignments: var1 = var2(initial)
			// assignments: var2 = var0(initial)
			// assignments: var3 = var1(initial)
			// assignments: var4 = var2(initial)
			currentStack.assertHeights(DUP2_X1_32_HEIGHTS);

			Expr var2 = pop();
			Expr var1 = pop();
			Expr var0 = pop();

			Type var5Type = assign_stack(baseHeight + 2, var0); // var5 = var0(initial)

			Type var0Type = assign_stack(baseHeight - 3, var1); // var0 = var1(initial)
			Type var1Type = assign_stack(baseHeight - 2, var2); // var1 = var2(initial)
			Type var3Type = assign_stack(baseHeight + 0, var1); // var3 = var1(initial)
			Type var4Type = assign_stack(baseHeight + 1, var2); // var4 = var2(initial)
			Type var2Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 2, var5Type)); // var2 = var5 = var0(initial)

			push(load_stack(baseHeight - 3, var0Type)); // push var0
			push(load_stack(baseHeight - 2, var1Type)); // push var1
			push(load_stack(baseHeight - 1, var2Type)); // push var2
			push(load_stack(baseHeight + 0, var3Type)); // push var3
			push(load_stack(baseHeight + 1, var4Type)); // push var4
		}
	}

	protected void _dup2_x2() {
		Type topType = peek().getType();
		int baseHeight = currentStack.height();
		save_stack(false);
		
		if(topType.getSize() == 2) {
			Type bottomType = currentStack.peek(1).getType();
			if (bottomType.getSize() == 2) {
				// 64x64
				// prestack: var2, var0 (height = 4)
				// poststack: var4, var2, var0
				// assignments: var0 = var2(initial)
				// assignments: var2 = var0(initial)
				// assignments: var4 = var2(initial)
				currentStack.assertHeights(DUP2_X2_64x64_HEIGHTS);

				Expr var2 = pop();
				Expr var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var4Type = assign_stack(baseHeight - 0, var2); // var4 = var2(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0;
				push(load_stack(baseHeight - 2, var2Type)); // push var2;
				push(load_stack(baseHeight - 0, var4Type)); // push var4;
			} else {
				//64x32
				// prestack: var2, var1, var0 (height = 4)
				// poststack: var4, var3, var2, var0
				// assignments: var0 = var2(initial)
				// assignments: var2 = var0(initial)
				// assignments: var3 = var1(initial)
				// assignments: var4 = var2(initial)
				currentStack.assertHeights(DUP2_X2_64x32_HEIGHTS);

				Expr var2 = pop();
				Expr var1 = pop();
				Expr var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2
				Type var3Type = assign_stack(baseHeight - 1, var1); // var3 = var1
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var0

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight - 1, var3Type)); // push var3
				push(load_stack(baseHeight + 0, var4Type)); // push var4
			}
		} else {
			Type bottomType = currentStack.peek(2).getType();
			if (bottomType.getSize() == 2) {
				// 32x64
				// prestack: var3, var2, var0 (height = 4)
				// poststack: var5, var4, var2, var1, var0
				// assignments: var0 = var2(initial)
				// assignments: var1 = var3(initial)
				// assignments: var2 = var0(initial)
				// assignments: var4 = var2(initial)
				// assignments: var5 = var3(initial)
				currentStack.assertHeights(DUP2_X2_32x64_HEIGHTS);

				Expr var3 = pop();
				Expr var2 = pop();
				Expr var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var1Type = assign_stack(baseHeight - 3, var3); // var1 = var3(initial)
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2(initial)
				Type var5Type = assign_stack(baseHeight + 1, var3); // var5 = var3(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 3, var1Type)); // push var1
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight + 0, var4Type)); // push var4
				push(load_stack(baseHeight + 1, var5Type)); // push var5
			} else {
				// 32x32
				// prestack: var3, var2, var1, var0 (height = 4)
				// poststack: var5, var4, var3, var2, var1, var0
				// var0 = var2
				// var1 = var3
				// var2 = var0
				// var3 = var1
				// var4 = var2
				// var5 = var3
				currentStack.assertHeights(DUP2_X2_32x32_HEIGHTS);

				Expr var3 = pop();
				Expr var2 = pop();
				Expr var1 = pop();
				Expr var0 = pop();

				Type var6Type = assign_stack(baseHeight + 2, var0); // var6 = var0(initial)
				Type var7Type = assign_stack(baseHeight + 3, var1); // var7 = var1(initial)

				Type var0Type = assign_stack(baseHeight - 4, var2); // var0 = var2(initial)
				Type var1Type = assign_stack(baseHeight - 3, var3); // var1 = var3(initial)
				Type var4Type = assign_stack(baseHeight + 0, var2); // var4 = var2(initial)
				Type var5Type = assign_stack(baseHeight + 1, var3); // var5 = var3(initial)
				Type var2Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 2, var6Type)); // var2 = var6 = var0(initial)
				Type var3Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 3, var7Type)); // var3 = var7 = var1(initial)

				push(load_stack(baseHeight - 4, var0Type)); // push var0
				push(load_stack(baseHeight - 3, var1Type)); // push var1
				push(load_stack(baseHeight - 2, var2Type)); // push var2
				push(load_stack(baseHeight - 1, var3Type)); // push var3
				push(load_stack(baseHeight + 0, var4Type)); // push var4
				push(load_stack(baseHeight + 1, var5Type)); // push var5
			}
		}
	}
	
	protected void _swap() {
		// prestack: var1, var0 (height = 2)
		// poststack: var1, var0
		// assignments: var0 = var1 (initial)
		// assignments: var1 = var0 (initial)

		currentStack.assertHeights(SWAP_HEIGHTS);
		int baseHeight = currentStack.height();
		save_stack(false);

		Expr var1 = pop();
		Expr var0 = pop();

		Type var2Type = assign_stack(baseHeight + 0, var0); // var2 = var0
		Type var3Type = assign_stack(baseHeight + 1, var1); // var3 = var1

		Type var0Type = assign_stack(baseHeight - 2, load_stack(baseHeight + 1, var3Type)); // var0 = var3 = var1(initial)
		Type var1Type = assign_stack(baseHeight - 1, load_stack(baseHeight + 0, var2Type)); // var1 = var2 = var0(initial)

		push(load_stack(baseHeight - 2, var0Type)); // push var0
		push(load_stack(baseHeight - 1, var1Type)); // push var1
	}
	
	protected void _cast(Type type) {
		save_stack(false);
		Expr e = new CastExpr(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _instanceof(Type type) {
		save_stack(false);
		InstanceofExpr e = new InstanceofExpr(pop(), type);
		int index = currentStack.height();
		assign_stack(index, e);
		push(load_stack(index, Type.BOOLEAN_TYPE));
	}
	
	protected void _new(Type type) {
		save_stack(false);
		int index = currentStack.height();
		UninitialisedObjectExpr e = new UninitialisedObjectExpr(type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _new_array(Expr[] bounds, Type type) {
		int index = currentStack.height();
		NewArrayExpr e = new NewArrayExpr(bounds, type);
		assign_stack(index, e);
		push(load_stack(index, type));
	}
	
	protected void _dynamic_call(Handle _bsm, Object[] _args, String name, String desc) {
		save_stack(false);
		Handle provider = new Handle(_bsm.getTag(), _bsm.getOwner(), _bsm.getName(), _bsm.getDesc());
		Object[] pArgs = new Object[_args.length];
		System.arraycopy(_args, 0, pArgs, 0, pArgs.length);
		
		// FIXME: can this end up as a virtual call
		Expr[] args = new Expr[Type.getArgumentTypes(desc).length];
		for(int i = args.length - 1; i >= 0; i--) {
			args[i] = pop();
		}
		
		DynamicInvocationExpr expr = new DynamicInvocationExpr(provider, pArgs, name, desc, args);
		if(expr.getType() == Type.VOID_TYPE) {
			addStmt(new PopStmt(expr));
		} else {
			int index = currentStack.height();
			Type type = assign_stack(index, expr);
			push(load_stack(index, type));
		}
		
		// TODO: redo vm lambdas as static resolution calls/concrete calls.
	}
	
	protected void _call(int op, String owner, String name, String desc) {
		save_stack(false);
		int argLen = Type.getArgumentTypes(desc).length + (op == INVOKESTATIC ? 0 : 1);
		Expr[] args = new Expr[argLen];
		for (int i = args.length - 1; i >= 0; i--) {
			args[i] = pop();
		}
		InvocationExpr callExpr = new InvocationExpr(op, args, owner, name, desc);
		if(callExpr.getType() == Type.VOID_TYPE) {
			addStmt(new PopStmt(callExpr));
		} else {
			int index = currentStack.height();
			Type type = assign_stack(index, callExpr);
			push(load_stack(index, type));
		}
	}
	
	protected void _switch(LinkedHashMap<Integer, BasicBlock> targets, BasicBlock dflt) {
		save_stack(false);
		Expr expr = pop();
		
		for (Entry<Integer, BasicBlock> e : targets.entrySet()) {
			update_target_stack(currentBlock, e.getValue(), currentStack);
		}
		
		update_target_stack(currentBlock, dflt, currentStack);
		
		addStmt(new SwitchStmt(expr, targets, dflt));
	}

	protected void _store_field(int opcode, String owner, String name, String desc) {
		save_stack(false);
		if(opcode == PUTFIELD) {
			Expr val = pop();
			Expr inst = pop();
			addStmt(new FieldStoreStmt(inst, val, owner, name, desc));
		} else if(opcode == PUTSTATIC) {
			Expr val = pop();
			addStmt(new FieldStoreStmt(null, val, owner, name, desc));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	protected void _load_field(int opcode, String owner, String name, String desc) {
		save_stack(false);
		if(opcode == GETFIELD || opcode == GETSTATIC) {
			Expr inst = null;
			if(opcode == GETFIELD) {
				inst = pop();
			}
			FieldLoadExpr fExpr = new FieldLoadExpr(inst, owner, name, desc);
			int index = currentStack.height();
			Type type = assign_stack(index, fExpr);
			push(load_stack(index, type));
		} else {
			throw new UnsupportedOperationException(Printer.OPCODES[opcode] + " " + owner + "." + name + "   " + desc);
		}
	}
	
	protected void _store(int index, Type type) {
		save_stack(false);
		Expr expr = pop();
		VarExpr var = _var_expr(index, expr.getType(), false);
		addStmt(copy(var, expr));
	}

	protected void _load(int index, Type type) {
		VarExpr e = _var_expr(index, type, false);
		// assign_stack(currentStack.height(), e);
		push(e);
	}

	protected void _inc(int index, int amt) {
		save_stack(false);
		VarExpr load = _var_expr(index, Type.INT_TYPE, false);
		ArithmeticExpr inc = new ArithmeticExpr(new ConstantExpr(amt), load, Operator.ADD);
		VarExpr var = _var_expr(index, Type.INT_TYPE, false);
		addStmt(copy(var, inc));
	}
	
	protected CopyVarStmt copy(VarExpr v, Expr e) {
		return copy(v, e, currentBlock);
	}
	
	protected CopyVarStmt copy(VarExpr v, Expr e, BasicBlock b) {
		builder.assigns.getNonNull(v.getLocal()).add(b);
		return new CopyVarStmt(v.getParent() != null? v.copy() : v, e.getParent() != null? e.copy() : e);
	}
	
	protected VarExpr _var_expr(int index, Type type, boolean isStack) {
		Local l = builder.graph.getLocals().get(index, isStack);
		builder.locals.add(l);
		return new VarExpr(l, type);
	}
	
	// var[index] = expr
	protected Type assign_stack(int index, Expr expr) {
		if(expr.getOpcode() == Opcode.LOCAL_LOAD) {
			VarExpr v = (VarExpr) expr;
			if(v.getIndex() == index && v.getLocal().isStack()) {
				return expr.getType();
			}
		}
		Type type = expr.getType();
		VarExpr var = _var_expr(index, type, true);
		CopyVarStmt stmt = copy(var, expr);
		addStmt(stmt);
		return type;
	}
	
	protected Expr load_stack(int index, Type type) {
		return _var_expr(index, type, true);
	}
	
	protected void _jump_cmp(BasicBlock target, ComparisonType type, Expr left, Expr right) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new ConditionalJumpStmt(left, right, target, type));
	}
	
	protected void _jump_cmp(BasicBlock target, ComparisonType type) {
		save_stack(false);
		Expr right = pop();
		Expr left = pop();
		_jump_cmp(target, type, left, right);
	}
	
	protected void _jump_cmp0(BasicBlock target, ComparisonType type) {
		save_stack(false);
		Expr left = pop();
		ConstantExpr right = new ConstantExpr(0);
		_jump_cmp(target, type, left, right);
	}

	protected void _jump_null(BasicBlock target, boolean invert) {
		save_stack(false);
		Expr left = pop();
		ConstantExpr right = new ConstantExpr(null);
		ComparisonType type = invert ? ComparisonType.NE : ComparisonType.EQ;
		
		_jump_cmp(target, type, left, right);
	}

	protected void _jump_uncond(BasicBlock target) {
		update_target_stack(currentBlock, target, currentStack);
		addStmt(new UnconditionalJumpStmt(target));
	}
	
	protected Expr _pop(Expr e) {
		if(e.getParent() != null) {
			return e.copy();
		} else {
			return e;
		}
	}
	
	protected Expr pop() {
		return _pop(currentStack.pop());
	}
	
	protected Expr peek() {
		return currentStack.peek();
	}

	protected void push(Expr e) {
		currentStack.push(e);
	}
	
	protected void addStmt(Stmt stmt) {
		currentBlock.add(stmt);
	}
	
	protected void save_stack() {
		save_stack(true);
	}
	
	protected void save_stack(boolean check) {
		// System.out.println("Saving " + currentBlock.getId());
		if (!currentBlock.isEmpty() && currentBlock.get(currentBlock.size() - 1).canChangeFlow()) {
			throw new IllegalStateException("Flow instruction already added to block; cannot save stack: "  + currentBlock.getId());
		}
		
		// System.out.println("\n   Befor: " + currentStack);
		// System.out.println("     With size: " + currentStack.size());
		// System.out.println("     With height: " + currentStack.height());
		
		ExpressionStack copy = currentStack.copy();
		int len = currentStack.size();
		currentStack.clear();
		
		int height = 0;
		for(int i=len-1; i >= 0; i--) {
			// peek(0) = top
			// peek(len-1) = btm

			int index = height;
			Expr expr = copy.peek(i);
			
			if(expr.getParent() != null) {
				expr = expr.copy();
			}
			
			// System.out.println("    Pop: " + expr + ":" + expr.getType());
			// System.out.println("    Idx: " + index);
			Type type = assign_stack(index, expr);
			Expr e = load_stack(index, type);
			// System.out.println("    Push " + e + ":" + e.getType());
			// System.out.println("    tlen: " + type.getSize());

			currentStack.push(e);
			
			height += type.getSize();
		}
		
		if(check) {
			saved = true;
		}
		
		//System.out.println("   After: " + currentStack + "\n");
	}

	protected boolean can_succeed(ExpressionStack s, ExpressionStack succ) {
		// quick check stack heights
		if (s.height() != succ.height()) {
			return false;
		}
		ExpressionStack c0 = s.copy();
		ExpressionStack c1 = succ.copy();
		while (c0.height() > 0) {
			Expr e1 = c0.pop();
			Expr e2 = c1.pop();
			if (!(e1.getOpcode() == Opcode.LOCAL_LOAD) || !(e2.getOpcode() == Opcode.LOCAL_LOAD)) {
				return false;
			}
			if (((VarExpr) e1).getIndex() != ((VarExpr) e2).getIndex()) {
				return false;
			}
			if (e1.getType().getSize() != e2.getType().getSize()) {
				return false;
			}
		}
		return true;
	}
	
	private void update_target_stack(BasicBlock b, BasicBlock target, ExpressionStack stack) {
		if(stacks.get(b.getNumericId()) && !saved) {
			save_stack();
		}
		// called just before a jump to a successor block may
		// happen. any operations, such as comparisons, that
		// happen before the jump are expected to have already
		// popped the left and right arguments from the stack before
		// checking the merge state.
		if (!stacks.get(target.getNumericId())) {
			// unfinalised block found.
			// System.out.println("Setting target stack of " + target.getId() + " to " + stack);
			target.setInputStack(stack.copy());
			stacks.set(target.getNumericId());

			queue(target.getLabelNode());
		} else if (!can_succeed(target.getInputStack(), stack)) {
			// if the targets input stack is finalised and
			// the new stack cannot merge into it, then there
			// is an error in the bytecode (verifier error).
			
			// BasicDotConfiguration<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
			// DotWriter<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, builder.graph);
			// writer.removeAll().add(new ControlFlowGraphDecorator().setFlags(ControlFlowGraphDecorator.OPT_DEEP)).setName("6996").export();
			
			System.err.println("Current: " + stack + " in " + b.getId());
			System.err.println("Target : " + target.getInputStack() + " in " + target.getId());
			System.err.println(builder.graph);
			throw new IllegalStateException("Stack coherency mismatch into #" + target.getId());
		}
	}
	
	private void makeRanges(List<BasicBlock> order) {
//		System.out.println(builder.graph);
//		BasicDotConfiguration<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
//		DotWriter<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, builder.graph);
//		writer.removeAll().add(new ControlFlowGraphDecorator().setFlags(ControlFlowGraphDecorator.OPT_DEEP)).setName("test9999").export();
		
		Map<String, ExceptionRange<BasicBlock>> ranges = new HashMap<>();
		for(TryCatchBlockNode tc : builder.method.tryCatchBlocks) {
			
//			System.out.printf("from %d to %d, handler:%d, type:%s.%n", insns.indexOf(tc.start), insns.indexOf(tc.end), insns.indexOf(tc.handler), tc.type);
//			System.out.println(String.format("%s:%s:%s", BasicBlock.createBlockName(insns.indexOf(tc.start)), BasicBlock.createBlockName(insns.indexOf(tc.end)), builder.graph.getBlock(tc.handler).getId()));
			
			int start = builder.graph.getBlock(tc.start).getNumericId();
			int end = builder.graph.getBlock(tc.end).getNumericId() - 1;
			
			List<BasicBlock> range = GraphUtils.range(order, start, end);
			BasicBlock handler = builder.graph.getBlock(tc.handler);
			String key = String.format("%s:%s:%s", BasicBlock.createBlockName(start), BasicBlock.createBlockName(end), handler.getId());
			
			ExceptionRange<BasicBlock> erange;
			if(ranges.containsKey(key)) {
				erange = ranges.get(key);
			} else {
				erange = new ExceptionRange<>(tc);
				erange.setHandler(handler);
				erange.addVertices(range);
				ranges.put(key, erange);
				
				if(!erange.isContiguous()) {
					System.out.println(erange + " not contiguous");
				}
				builder.graph.addRange(erange);
			}
			
			erange.addType(tc.type);
			
			ListIterator<BasicBlock> lit = range.listIterator();
			while(lit.hasNext()) {
				BasicBlock block = lit.next();
				builder.graph.addEdge(block, new TryCatchEdge<>(block, erange));
			}
		}
	}
	
	private void ensureMarks() {
		// it is possible for the start/end blocks of ranges
		// to not be generated/blocked during generation,
		// so we generate them here.
		
		for(LabelNode m : marks) {
			// creates the block if it's not
			// already in the graph.
			resolveTarget(m);
		}
		// queue is irrelevant at this point.
		queue.clear();
		
		// since the blocks created were not reached
		// it means that their inputstacks were empty.
		// this also means no edges are needed to connect
		// them except for the range edges which are done
		// later.
		// we can also rely on the natural label ordering
		// code to fix up the graph to make it look like
		// this block is next to the previous block in code.
	}
	
	private void processQueue() {
		while(!queue.isEmpty()) {
			LabelNode label = queue.removeFirst();
			process(label);
		}
		
		ensureMarks();
		
		List<BasicBlock> blocks = new ArrayList<>(builder.graph.vertices());
		Collections.sort(blocks, new Comparator<BasicBlock>() {
			@Override
			public int compare(BasicBlock o1, BasicBlock o2) {
				int i1 = insns.indexOf(o1.getLabelNode());
				int i2 = insns.indexOf(o2.getLabelNode());
				return Integer.compare(i1, i2);
			}
		});
		
		builder.naturaliseGraph(blocks);
		makeRanges(blocks);
	}

	@Override
	public void run() {
		if(builder.count == 0) { // no blocks created
			init();
			processQueue();
		}
	}
}