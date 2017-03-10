package org.apache.calcite.adapter.jdbc;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.adapter.jdbc.tools.JdbcRelBuilder;
import org.apache.calcite.adapter.jdbc.tools.JdbcRelBuilderFactory;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.List;

class JournalledJdbcTable extends JdbcTable {
	private final JdbcTable journalTable;
	private final JournalledJdbcSchema journalledJdbcSchema;
	private final ImmutableList<String> keyColumnNames;
	JdbcRelBuilderFactory relBuilderFactory = new JdbcRelBuilder.Factory(null);

	JournalledJdbcTable(
			String tableName,
			JournalledJdbcSchema journalledJdbcSchema,
			JdbcTable journalTable,
			String[] keyColumnNames
	) {
		super(
				journalledJdbcSchema,
				JdbcTableUtils.getCatalogName(journalTable),
				JdbcTableUtils.getSchemaName(journalTable),
				tableName,
				journalTable.getJdbcTableType()
		);
		this.journalTable = journalTable;
		this.journalledJdbcSchema = journalledJdbcSchema;
		this.keyColumnNames = ImmutableList.copyOf(keyColumnNames);
	}

	@Override
	public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
		JdbcRelBuilder relBuilder = relBuilderFactory.create(
				context.getCluster(),
				relOptTable.getRelOptSchema()
		);

		// FROM <table_journal>
		relBuilder.scanJdbc(
				journalTable,
				JdbcTableUtils.getQualifiedName(relOptTable, journalTable)
		);

		RexInputRef versionField = relBuilder.field(journalledJdbcSchema.getVersionField());
		RexInputRef subsequentVersionField = relBuilder.field(journalledJdbcSchema.getSubsequentVersionField());

		// <maxVersionField> = MAX(<version_number>) OVER (PARTITION BY <primary_key>)
		RexInputRef maxVersionField = relBuilder.appendField(relBuilder.makeOver(
				SqlStdOperatorTable.MAX,
				ImmutableList.of(versionField),
				relBuilder.fields(keyColumnNames)
		));

		// WHERE <version_field> = <maxVersionField> AND <subsequent_version_field> IS NULL
		relBuilder.filter(
				relBuilder.equals(versionField, maxVersionField),
				relBuilder.isNull(subsequentVersionField)
		);

		return relBuilder.build();
	}

	@Override public TableModify toModificationRel(
			RelOptCluster cluster,
			RelOptTable table,
			Prepare.CatalogReader catalogReader,
			RelNode input,
			TableModify.Operation operation,
			List<String> updateColumnList,
			List<RexNode> sourceExpressionList,
			boolean flattened
	) {
		List<String> names = JdbcTableUtils.getQualifiedName(table, journalTable);
		RelOptTable relOptJournalTable = table.getRelOptSchema().getTableForMember(names);
		return journalTable.toModificationRel(
				cluster,
				relOptJournalTable,
				catalogReader,
				input,
				operation,
				updateColumnList,
				sourceExpressionList,
				flattened
		);
	}
}