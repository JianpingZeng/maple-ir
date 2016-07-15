package org.rsdeob.stdlib.ir;

import org.rsdeob.stdlib.cfg.util.TabbedStringWriter;
import org.rsdeob.stdlib.ir.api.ICodeListener;
import org.rsdeob.stdlib.ir.header.StatementHeaderStatement;
import org.rsdeob.stdlib.ir.locals.LocalsHandler;
import org.rsdeob.stdlib.ir.stat.Statement;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class CodeBody implements List<Statement> {

	private final LocalsHandler locals;
	private final ArrayList<Statement> stmts;
	private final List<ICodeListener<Statement>> listeners;
	
	public CodeBody(int maxL) {
		stmts = new ArrayList<>();
		locals = new LocalsHandler(maxL + 1);
		listeners = new CopyOnWriteArrayList<>();
	}

	public void registerListener(ICodeListener<Statement> listener) {
		listeners.add(listener);
	}
	
	public void unregisterListener(ICodeListener<Statement> listener) {
		listeners.remove(listener);
	}

	public void clearListeners() {
		listeners.clear();
	}

	public List<ICodeListener<Statement>> getListeners() {
		return new ArrayList<>(listeners);
	}
	
	public LocalsHandler getLocals() {
		return locals;
	}

	public void forceUpdate(Statement stmt) {
		for(ICodeListener<Statement> l : listeners)
			l.update(stmt);
	}

	public void commit() {
		for(ICodeListener<Statement> l : listeners)
			l.commit();
	}

	public void toString(TabbedStringWriter printer) {
		for (int addr = 0; addr < stmts.size(); addr++) {
			Statement stmt = stmts.get(addr);
			if(!(stmt instanceof StatementHeaderStatement)) {
				printer.print(stmt.getId() + ". ");
				stmt.toString(printer);
			}

			if (addr < stmts.size() - 1) {
				Statement next = stmts.get(addr + 1);
				if (next != null) {
					if (!(stmt instanceof StatementHeaderStatement)) {
						printer.print('\n', !next.changesIndentation());
					}
				}
			}
		}
	}

	@Override
	public String toString() {
		TabbedStringWriter printer = new TabbedStringWriter();
		toString(printer);
		return printer.toString();
	}

	@Override
	public Statement remove(int index) {
		Statement p = get(index);
		return p != null && !remove(p) ? null : p;
	}

	@Override
	public boolean remove(Object o) {
		if (!(o instanceof Statement))
			return false;
		Statement s = (Statement) o;

		for(ICodeListener<Statement> l : listeners)
			l.preRemove(s);

		boolean ret = stmts.remove(s);
		for(ICodeListener<Statement> l : listeners)
			l.postRemove(s);

		return ret;
	}

	@Override
	public void add(int index, Statement stmt) {
//		if(!contains(s)) {
//			stmts.add(index, s);
//			for(ICodeListener<Statement> l : listeners)
//				l.update(s);
//		}
		Statement p = stmts.get(index);
		Statement s = stmts.get(index + 1);
		stmts.add(index, stmt);
		for(ICodeListener<Statement> l : listeners) {
			l.insert(p, s, stmt);
		}
	}

	@Override
	public boolean add(Statement s) {
		boolean ret = stmts.add(s);
		for(ICodeListener<Statement> l : listeners)
			l.update(s);
		return ret;
	}

	@Override
	public Statement set(int index, Statement element) {
		Statement old = stmts.set(index, element);
		for(ICodeListener<Statement> l : listeners)
			l.replaced(old, element);
		return old;
	}

	@Override
	public boolean addAll(Collection<? extends Statement> c) {
		boolean result = false;
		for (Statement s : c)
			result = result || add(s);
		return result;
	}

	@Override
	public boolean addAll(int index, Collection<? extends Statement> c) {
		for (Statement s : c)
			add(index, s);
		return c.size() != 0;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		boolean result = false;
		for (Object o : c)
			result = result || remove(o);
		return result;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		boolean result = false;
		for (Object o : c)
			if (!contains(o))
				result = result || remove(o);
		return result;
	}

	@Override
	public void clear() {
		forEach(this::remove);
	}

	@Override
	public int size() {
		return stmts.size();
	}

	@Override
	public int indexOf(Object stmt) {
		return stmts.indexOf(stmt);
	}

	@Override
	public int lastIndexOf(Object o) {
		return stmts.lastIndexOf(o);
	}

	@Override
	public Statement get(int index) {
		return stmts.get(index);
	}

	@Override
	public Iterator<Statement> iterator() {
		return stmts.iterator();
	}

	@Override
	public ListIterator<Statement> listIterator() {
		return stmts.listIterator();
	}

	@Override
	public ListIterator<Statement> listIterator(int index) {
		return stmts.listIterator(index);
	}

	@Override
	public CodeBody subList(int fromIndex, int toIndex) {
		throw new UnsupportedOperationException("CodeBody does not support subList");
	}

	@Override
	public Object[] toArray() {
		return stmts.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return stmts.toArray(a);
	}

	@Override
	public boolean isEmpty() {
		return stmts.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return stmts.contains(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return stmts.containsAll(c);
	}
}