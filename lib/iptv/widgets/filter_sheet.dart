import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;
import 'package:material_ui/material_ui.dart';

/// Multi-select group / country filter (country codes shown with their full names
/// from assets/json/country.json, as in the reference app).
class FilterSheet extends StatefulWidget {
  const FilterSheet({
    super.key,
    required this.groups,
    required this.countries,
    required this.selectedGroups,
    required this.selectedCountries,
  });

  final List<String> groups;
  final List<String> countries;
  final Set<String> selectedGroups;
  final Set<String> selectedCountries;

  @override
  State<FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<FilterSheet> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);
  late final Set<String> _groups = {...widget.selectedGroups};
  late final Set<String> _countries = {...widget.selectedCountries};
  Map<String, String> _countryNames = {};
  String _query = '';

  @override
  void initState() {
    super.initState();
    rootBundle.loadString('assets/json/country.json').then((s) {
      final data = jsonDecode(s) as List;
      if (mounted) {
        setState(() => _countryNames = {for (final c in data) c['code'] as String: c['name'] as String});
      }
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.toLowerCase();
    final groups = widget.groups.where((g) => g.toLowerCase().contains(q)).toList();
    final countries = widget.countries
        .where((c) => c.toLowerCase().contains(q) || (_countryNames[c] ?? '').toLowerCase().contains(q))
        .toList();

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: SizedBox(
          height: MediaQuery.of(context).size.height * 0.75,
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: TextField(
                decoration: const InputDecoration(prefixIcon: Icon(Icons.search), hintText: 'Search filters', border: OutlineInputBorder()),
                onChanged: (v) => setState(() => _query = v),
              ),
            ),
            TabBar(controller: _tabs, tabs: [
              Tab(text: 'Categories${_groups.isEmpty ? '' : ' (${_groups.length})'}'),
              Tab(text: 'Countries${_countries.isEmpty ? '' : ' (${_countries.length})'}'),
            ]),
            Expanded(
              child: TabBarView(controller: _tabs, children: [
                ListView.builder(
                  itemCount: groups.length,
                  itemBuilder: (_, i) => CheckboxListTile(
                    title: Text(groups[i]),
                    value: _groups.contains(groups[i]),
                    onChanged: (v) => setState(() => v == true ? _groups.add(groups[i]) : _groups.remove(groups[i])),
                  ),
                ),
                ListView.builder(
                  itemCount: countries.length,
                  itemBuilder: (_, i) => CheckboxListTile(
                    title: Text(_countryNames[countries[i]] ?? countries[i]),
                    subtitle: _countryNames[countries[i]] == null ? null : Text(countries[i]),
                    value: _countries.contains(countries[i]),
                    onChanged: (v) => setState(() => v == true ? _countries.add(countries[i]) : _countries.remove(countries[i])),
                  ),
                ),
              ]),
            ),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Row(children: [
                TextButton(onPressed: () => setState(() { _groups.clear(); _countries.clear(); }), child: const Text('Clear')),
                const Spacer(),
                FilledButton(
                  onPressed: () => Navigator.pop(context, (_groups, _countries)),
                  child: const Text('Apply'),
                ),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}
