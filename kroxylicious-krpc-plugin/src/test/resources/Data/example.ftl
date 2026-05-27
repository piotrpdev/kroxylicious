<#--

    Copyright Kroxylicious Authors.

    Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0

-->
<#-- ============================================================
     Helper functions (no output produced)
     ============================================================ -->

<#-- Convert PascalCase field name to snake_case for Schema Field declarations -->
<#function snakeCase name>
  <#return name?replace("(?<=[a-z])([A-Z])", "_$1", "r")?lower_case>
</#function>

<#-- Returns true if the struct's schema changes between version-1 and version.
     Checks field appearances/disappearances, tagged section changes, and
     serialization format changes. Recursively checks sub-struct schemas. -->
<#function structSchemaChangesAt struct version>
  <#list struct.fields as field>
    <#local fieldIsTaggedAtV   = field.taggedVersions.contains(version)>
    <#local fieldIsTaggedAtPrev = field.taggedVersions.contains(version - 1)>
    <#local fieldPresentAtV    = field.versions.contains(version)>
    <#local fieldPresentAtPrev = field.versions.contains(version - 1)>
    <#-- Non-tagged field appears at this version -->
    <#if !fieldIsTaggedAtV && fieldPresentAtV && (!fieldPresentAtPrev || fieldIsTaggedAtPrev)>
      <#return true>
    </#if>
    <#-- Non-tagged field disappears at this version -->
    <#if !fieldIsTaggedAtPrev && fieldPresentAtPrev && (!fieldPresentAtV || fieldIsTaggedAtV)>
      <#return true>
    </#if>
    <#-- Tagged field appears in TaggedFieldsSection -->
    <#if fieldIsTaggedAtV && fieldPresentAtV && !(fieldIsTaggedAtPrev && fieldPresentAtPrev)>
      <#return true>
    </#if>
    <#-- Tagged field disappears from TaggedFieldsSection -->
    <#if fieldIsTaggedAtPrev && fieldPresentAtPrev && !(fieldIsTaggedAtV && fieldPresentAtV)>
      <#return true>
    </#if>
    <#-- Serialization format changes (flexible/non-flexible) for non-tagged field present in both -->
    <#if fieldPresentAtV && fieldPresentAtPrev && !fieldIsTaggedAtV && !fieldIsTaggedAtPrev && field.type.serializationIsDifferentInFlexibleVersions>
      <#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
      <#if effFlex.contains(version) != effFlex.contains(version - 1)>
        <#return true>
      </#if>
    </#if>
    <#-- Sub-struct schema changes -->
    <#if (field.type.isStructArray || field.type.isStruct) && fieldPresentAtV && fieldPresentAtPrev && !fieldIsTaggedAtV && !fieldIsTaggedAtPrev>
      <#if structSchemaChangesAt(structRegistry.findStruct(field), version)>
        <#return true>
      </#if>
    </#if>
  </#list>
  <#return false>
</#function>

<#-- Returns the canonical schema version number for a struct at a given version.
     Walks from effectiveLow up to version, finding the last version where the schema changed. -->
<#function resolveSchemaVersion struct version effectiveLow>
  <#local canonical = effectiveLow>
  <#list effectiveLow..version as v>
    <#if (v gt effectiveLow) && structSchemaChangesAt(struct, v)>
      <#local canonical = v>
    </#if>
  </#list>
  <#return canonical>
</#function>

<#-- Compute effective lowest version for a struct (max of struct's lowest and message's lowest) -->
<#function effectiveLow struct>
  <#local sl = struct.versions.lowest>
  <#local ml = inputSpec.validVersions.lowest>
  <#if (sl gt ml)><#return sl><#else><#return ml></#if>
</#function>

<#-- Boxed Java type for generic type parameters -->
<#function boxedElementType elemType>
  <#local t = elemType?string>
  <#if t == 'int32'><#return "Integer">
  <#elseif t == 'int64'><#return "Long">
  <#elseif t == 'int8'><#return "Byte">
  <#elseif t == 'int16'><#return "Short">
  <#elseif t == 'bool'><#return "Boolean">
  <#elseif t == 'uuid'><#return "Uuid">
  <#else><#return t>
  </#if>
</#function>

<#-- ============================================================
     Output macros
     ============================================================ -->

<#-- Java type for a field declaration -->
<#macro javaFieldType field><#compress>
<#if field.type == 'int32'>int
<#elseif field.type == 'int64'>long
<#elseif field.type == 'int8'>byte
<#elseif field.type == 'int16'>short
<#elseif field.type == 'bool'>boolean
<#elseif field.type == 'uuid'>Uuid
<#elseif field.type == 'string'>String
<#elseif field.type == 'float64'>double
<#elseif field.type.isStructArray>List<${field.type.elementName}>
<#elseif field.type.isArray>List<${boxedElementType(field.type.elementType)}>
<#elseif field.type.isStruct>${field.type}
<#else>Object</#if>
</#compress></#macro>

<#-- Default value expression for a field -->
<#macro fieldDefault field><#compress>
<#if field.type == 'int64'><#if field.defaultString == ''>0L<#else>${field.defaultString}L</#if>
<#elseif field.type == 'int8'><#if field.defaultString == ''>(byte) 0<#else>(byte) ${field.defaultString}</#if>
<#elseif field.type == 'int16'><#if field.defaultString == ''>(short) 0<#else>(short) ${field.defaultString}</#if>
<#elseif field.type == 'int32'><#if field.defaultString == ''>0<#else>${field.defaultString}</#if>
<#elseif field.type == 'bool'><#if field.defaultString == ''>false<#else>${field.defaultString}</#if>
<#elseif field.type == 'uuid'>Uuid.ZERO_UUID
<#elseif field.type == 'string'><#if field.defaultString == 'null'>null<#elseif field.defaultString == ''>""<#else>"${field.defaultString}"</#if>
<#elseif field.type.isStruct>new ${field.type}()
<#elseif field.type.isStructArray>new ArrayList<${field.type.elementName}>(0)
<#elseif field.type.isArray>new ArrayList<${boxedElementType(field.type.elementType)}>(0)
<#else>null</#if>
</#compress></#macro>

<#-- Schema Type.XXX for a primitive type -->
<#macro primitiveSchemaType type><#compress>
<#if type == 'int32'>Type.INT32
<#elseif type == 'int64'>Type.INT64
<#elseif type == 'int8'>Type.INT8
<#elseif type == 'int16'>Type.INT16
<#elseif type == 'bool'>Type.BOOLEAN
<#elseif type == 'uuid'>Type.UUID
<#else>Type.UNKNOWN</#if>
</#compress></#macro>

<#-- Schema type expression for a field at a given version -->
<#macro schemaFieldType field version><#compress>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#local isFlexible = effFlex.contains(version)>
<#if field.type == 'int32'>Type.INT32
<#elseif field.type == 'int64'>Type.INT64
<#elseif field.type == 'int8'>Type.INT8
<#elseif field.type == 'int16'>Type.INT16
<#elseif field.type == 'bool'>Type.BOOLEAN
<#elseif field.type == 'uuid'>Type.UUID
<#elseif field.type == 'string'>
  <#local isNullable = field.nullableVersions?has_content && field.nullableVersions.contains(version)>
  <#if isFlexible><#if isNullable>Type.COMPACT_NULLABLE_STRING<#else>Type.COMPACT_STRING</#if>
  <#else><#if isNullable>Type.NULLABLE_STRING<#else>Type.STRING</#if></#if>
<#elseif field.type.isArray>
  <#local elemType = field.type.elementType>
  <#if field.type.isStructArray>
    <#local substruct = structRegistry.findStruct(field)>
    <#local effLo = effectiveLow(substruct)>
    <#if isFlexible>new CompactArrayOf(${elemType}.SCHEMA_${resolveSchemaVersion(substruct, version, effLo)})<#else>new ArrayOf(${elemType}.SCHEMA_${resolveSchemaVersion(substruct, version, effLo)})</#if>
  <#else>
    <#if isFlexible>new CompactArrayOf(<@primitiveSchemaType type=elemType/>)<#else>new ArrayOf(<@primitiveSchemaType type=elemType/>)</#if>
  </#if>
<#elseif field.type.isStruct>
  <#local substruct = structRegistry.findStruct(field)>
  <#local effLo = effectiveLow(substruct)>
  ${field.type}.SCHEMA_${resolveSchemaVersion(substruct, version, effLo)}
<#else>Type.UNKNOWN</#if>
</#compress></#macro>

<#-- Generates all SCHEMA_V constants for a struct -->
<#macro generateStructSchemas struct effLo effHi>
<#local prevVer = -1>
<#list effLo..effHi as version>
<#local isNew = (version == effLo) || structSchemaChangesAt(struct, version)>
<#if isNew>
<#-- Collect non-tagged and tagged fields for this version -->
<#local nf = []>
<#list struct.fields as field>
<#if field.versions.contains(version) && !field.taggedVersions.contains(version)>
<#local nf = nf + [field]>
</#if>
</#list>
<#local tf = []>
<#list struct.fields as field>
<#if field.versions.contains(version) && field.taggedVersions.contains(version)>
<#local tf = tf + [field]>
</#if>
</#list>
<#local isFlexible = inputSpec.flexibleVersions.contains(version)>
    public static final Schema SCHEMA_${version} =
        new Schema(
<#list nf as field>
<#if field?has_next || tf?has_content>
            new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}"),
<#else>
            new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}")
</#if>
</#list>
<#if isFlexible>
<#if nf?has_content>
            TaggedFieldsSection.of(
<#list tf as field>
<#if field?has_next>
                ${field.tagInteger}, new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}"),
<#else>
                ${field.tagInteger}, new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}")
</#if>
</#list>
            )
<#else>
            TaggedFieldsSection.of(
<#list tf as field>
<#if field?has_next>
                ${field.tagInteger}, new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}"),
<#else>
                ${field.tagInteger}, new Field("${snakeCase(field.name)}", <@schemaFieldType field=field version=version/>, "${field.about}")
</#if>
</#list>
            )
</#if>
</#if>
        );
    <#local prevVer = version>
<#else>
    public static final Schema SCHEMA_${version} = SCHEMA_${prevVer};
    </#if>
</#list>
</#macro>

<#-- Generates SCHEMAS[] array with null entries for unsupported versions -->
<#macro generateSchemasArray effLo effHi>
    public static final Schema[] SCHEMAS = new Schema[] {
<#list 0..effLo-1 as i>
        null,
</#list>
<#list effLo..effHi as version>
<#if version?has_next>
        SCHEMA_${version},
<#else>
        SCHEMA_${version}
</#if>
</#list>
    };
</#macro>

<#-- Read a primitive field value -->
<#macro readPrimitive field>
<#compress>
<#if field.type == 'int32'>_readable.readInt()
<#elseif field.type == 'int64'>_readable.readLong()
<#elseif field.type == 'int8'>_readable.readByte()
<#elseif field.type == 'int16'>_readable.readShort()
<#elseif field.type == 'bool'>_readable.readByte() != 0
<#elseif field.type == 'uuid'>_readable.readUuid()
<#else>null /* UNKNOWN */</#if>
</#compress>
</#macro>

<#-- Read an array field (compact or non-compact based on flex version) -->
<#macro readArrayField field indent effLo>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#local elemName = field.type.elementName>
<#local isStruct = field.type.isStructArray>
<#local boxedElem = isStruct?then(elemName, boxedElementType(field.type.elementType))>
<#local readElem = isStruct?then("new ${elemName}(_readable, _version)", "_readable.readInt()")>
<#if !field.type.isStructArray && field.type.elementType == 'int64'>
  <#local readElem = "_readable.readLong()">
<#elseif !field.type.isStructArray && field.type.elementType == 'int8'>
  <#local readElem = "_readable.readByte()">
</#if>
<#if effFlex?has_content>
${indent}{
${indent}    if (_version >= ${effFlex.lowest}) {
${indent}        int arrayLength;
${indent}        arrayLength = _readable.readUnsignedVarint() - 1;
${indent}        if (arrayLength < 0) {
${indent}            throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
${indent}        } else {
${indent}            if (arrayLength > _readable.remaining()) {
${indent}                throw new RuntimeException("Tried to allocate a collection of size " + arrayLength + ", but there are only " + _readable.remaining() + " bytes remaining.");
${indent}            }
${indent}            ArrayList<${boxedElem}> newCollection = new ArrayList<>(arrayLength);
${indent}            for (int i = 0; i < arrayLength; i++) {
${indent}                newCollection.add(${readElem});
${indent}            }
${indent}            this.${field.name?uncap_first} = newCollection;
${indent}        }
${indent}    } else {
${indent}        int arrayLength;
${indent}        arrayLength = _readable.readInt();
${indent}        if (arrayLength < 0) {
${indent}            throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
${indent}        } else {
${indent}            if (arrayLength > _readable.remaining()) {
${indent}                throw new RuntimeException("Tried to allocate a collection of size " + arrayLength + ", but there are only " + _readable.remaining() + " bytes remaining.");
${indent}            }
${indent}            ArrayList<${boxedElem}> newCollection = new ArrayList<>(arrayLength);
${indent}            for (int i = 0; i < arrayLength; i++) {
${indent}                newCollection.add(${readElem});
${indent}            }
${indent}            this.${field.name?uncap_first} = newCollection;
${indent}        }
${indent}    }
${indent}}
<#else>
${indent}{
${indent}    int arrayLength;
${indent}    arrayLength = _readable.readInt();
${indent}    if (arrayLength < 0) {
${indent}        throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
${indent}    } else {
${indent}        if (arrayLength > _readable.remaining()) {
${indent}            throw new RuntimeException("Tried to allocate a collection of size " + arrayLength + ", but there are only " + _readable.remaining() + " bytes remaining.");
${indent}        }
${indent}        ArrayList<${boxedElem}> newCollection = new ArrayList<>(arrayLength);
${indent}        for (int i = 0; i < arrayLength; i++) {
${indent}            newCollection.add(${readElem});
${indent}        }
${indent}        this.${field.name?uncap_first} = newCollection;
${indent}    }
${indent}}
</#if>
</#macro>

<#-- Read a string field (non-nullable) -->
<#macro readStringField field indent effFlex>
<#if effFlex?has_content>
${indent}int length;
${indent}if (_version >= ${effFlex.lowest}) {
${indent}    length = _readable.readUnsignedVarint() - 1;
${indent}} else {
${indent}    length = _readable.readShort();
${indent}}
${indent}if (length < 0) {
${indent}    throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
${indent}} else if (length > 0x7fff) {
${indent}    throw new RuntimeException("string field ${field.name?uncap_first} had invalid length " + length);
${indent}} else {
${indent}    this.${field.name?uncap_first} = _readable.readString(length);
${indent}}
<#else>
${indent}int length;
${indent}length = _readable.readShort();
${indent}if (length < 0) {
${indent}    throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
${indent}} else if (length > 0x7fff) {
${indent}    throw new RuntimeException("string field ${field.name?uncap_first} had invalid length " + length);
${indent}} else {
${indent}    this.${field.name?uncap_first} = _readable.readString(length);
${indent}}
</#if>
</#macro>

<#-- Generates the read() method body -->
<#macro generateRead struct effLo effHi dataClass isTopLevel>
<#local flexLow = inputSpec.flexibleVersions?has_content?then(inputSpec.flexibleVersions.lowest, 32767)>
    @Override
    public final void read(Readable _readable, short _version) {
<#if !isTopLevel>
<#if effLo == inputSpec.validVersions.lowest>
        if ((_version < ${effLo}) || (_version > ${effHi})) {
            throw new UnsupportedVersionException("Can't read version " + _version + " of ${struct.name}");
        }
<#else>
        if (_version > ${effHi}) {
            throw new UnsupportedVersionException("Can't read version " + _version + " of ${struct.name}");
        }
</#if>
</#if>
<#list struct.fields as field>
<#local isTagged = field.taggedVersions?has_content && field.taggedVersions.lowest <= effHi>
<#if isTagged>
        {
            this.${field.name?uncap_first} = <@fieldDefault field=field/>;
        }
<#else>
<#-- Determine version guards -->
<#local alwaysPresent = (field.versions.lowest <= effLo) && (field.versions.highest >= effHi)>
<#local fromVersion = !alwaysPresent && (field.versions.lowest gt effLo) && (field.versions.highest gte effHi)>
<#local throughVersion = !alwaysPresent && (field.versions.lowest <= effLo) && (field.versions.highest < effHi)>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#if fromVersion>
        if (_version >= ${field.versions.lowest}) {
<#if field.type == 'string'>
<@readStringField field=field indent="            " effFlex=effFlex/>
<#elseif field.type.isArray>
<@readArrayField field=field indent="            " effLo=effLo/>
<#elseif field.type.isStruct>
            this.${field.name?uncap_first} = new ${field.type}(_readable, _version);
<#else>
            this.${field.name?uncap_first} = <@readPrimitive field=field/>;
</#if>
        } else {
            this.${field.name?uncap_first} = <@fieldDefault field=field/>;
        }
<#elseif throughVersion>
        if (_version <= ${field.versions.highest}) {
<#if field.type == 'string'>
<@readStringField field=field indent="            " effFlex=effFlex/>
<#elseif field.type.isArray>
<@readArrayField field=field indent="            " effLo=effLo/>
<#elseif field.type.isStruct>
            this.${field.name?uncap_first} = new ${field.type}(_readable, _version);
<#else>
            this.${field.name?uncap_first} = <@readPrimitive field=field/>;
</#if>
        } else {
            this.${field.name?uncap_first} = <@fieldDefault field=field/>;
        }
<#else>
<#-- always present -->
<#if field.type == 'string'>
        {
<@readStringField field=field indent="            " effFlex=effFlex/>
        }
<#elseif field.type.isArray>
<@readArrayField field=field indent="        " effLo=effLo/>
<#elseif field.type.isStruct>
        this.${field.name?uncap_first} = new ${field.type}(_readable, _version);
<#else>
        this.${field.name?uncap_first} = <@readPrimitive field=field/>;
</#if>
</#if>
</#if>
</#list>
        this._unknownTaggedFields = null;
<#if inputSpec.flexibleVersions?has_content>
        if (_version >= ${flexLow}) {
            int _numTaggedFields = _readable.readUnsignedVarint();
            for (int _i = 0; _i < _numTaggedFields; _i++) {
                int _tag = _readable.readUnsignedVarint();
                int _size = _readable.readUnsignedVarint();
                switch (_tag) {
<#list struct.fields as field>
<#if field.taggedVersions?has_content && field.versions?has_content && (field.taggedVersions.lowest <= effHi)>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
                    case ${field.tagInteger}: {
<#if (field.versions.lowest gt effLo)>
                        if (_version >= ${field.versions.lowest}) {
<#if field.type == 'string'>
<#local isNullable = field.nullableVersions?has_content>
                            int length;
                            length = _readable.readUnsignedVarint() - 1;
<#if isNullable>
                            if (length < 0) {
                                this.${field.name?uncap_first} = null;
                            } else if (length > 0x7fff) {
                                throw new RuntimeException("string field ${field.name?uncap_first} had invalid length " + length);
                            } else {
                                this.${field.name?uncap_first} = _readable.readString(length);
                            }
<#else>
                            if (length < 0) {
                                throw new RuntimeException("non-nullable field ${field.name?uncap_first} was serialized as null");
                            } else if (length > 0x7fff) {
                                throw new RuntimeException("string field ${field.name?uncap_first} had invalid length " + length);
                            } else {
                                this.${field.name?uncap_first} = _readable.readString(length);
                            }
</#if>
<#elseif field.type.isStruct>
                            this.${field.name?uncap_first} = new ${field.type}(_readable, _version);
<#elseif field.type == 'uuid'>
                            this.${field.name?uncap_first} = _readable.readUuid();
<#elseif field.type == 'int64'>
                            this.${field.name?uncap_first} = _readable.readLong();
<#elseif field.type == 'int32'>
                            this.${field.name?uncap_first} = _readable.readInt();
</#if>
                            break;
                        } else {
                            throw new RuntimeException("Tag ${field.tagInteger} is not valid for version " + _version);
                        }
<#else>
<#-- tagged field available in all flex versions -->
<#if field.type == 'string'>
<#local isNullable = field.nullableVersions?has_content>
                        int length;
                        length = _readable.readUnsignedVarint() - 1;
<#if isNullable>
                        if (length < 0) {
                            this.${field.name?uncap_first} = null;
                        } else if (length > 0x7fff) {
                            throw new RuntimeException("string field ${field.name?uncap_first} had invalid length " + length);
                        } else {
                            this.${field.name?uncap_first} = _readable.readString(length);
                        }
</#if>
                        break;
<#elseif field.type.isStruct>
                        this.${field.name?uncap_first} = new ${field.type}(_readable, _version);
                        break;
</#if>
</#if>
                    }
</#if>
</#list>
                    default:
                        this._unknownTaggedFields = _readable.readUnknownTaggedField(this._unknownTaggedFields, _tag, _size);
                        break;
                }
            }
        }
</#if>
    }
</#macro>

<#-- Non-default check expression for write/addSize error messages -->
<#macro nonDefaultCheck field><#compress>
<#if field.type == 'int32' || field.type == 'int8' || field.type == 'int16'>
this.${field.name?uncap_first} != ${(field.defaultString == '')?then("0", field.defaultString)}
<#elseif field.type == 'int64'>
this.${field.name?uncap_first} != ${(field.defaultString == '')?then("0L", field.defaultString + "L")}
<#elseif field.type == 'bool'>
this.${field.name?uncap_first} != ${(field.defaultString == '')?then("false", field.defaultString)}
<#elseif field.type == 'uuid'>
!this.${field.name?uncap_first}.equals(Uuid.ZERO_UUID)
<#elseif field.type == 'string'>
<#if field.nullableVersions?has_content>this.${field.name?uncap_first} != null<#else>!this.${field.name?uncap_first}.isEmpty()</#if>
<#elseif field.type.isStruct>
!this.${field.name?uncap_first}.equals(new ${field.type}())
<#elseif field.type.isArray>
!this.${field.name?uncap_first}.isEmpty()
<#else>
this.${field.name?uncap_first} != null</#if>
</#compress></#macro>

<#-- Generates the write() method body -->
<#macro generateWrite struct effLo effHi dataClass isTopLevel>
<#local flexLow = inputSpec.flexibleVersions?has_content?then(inputSpec.flexibleVersions.lowest, 32767)>
    @Override
    public void write(Writable _writable, ObjectSerializationCache _cache, short _version) {
<#if !isTopLevel && (effLo gt inputSpec.validVersions.lowest)>
        if (_version < ${effLo}) {
            throw new UnsupportedVersionException("Can't write version " + _version + " of ${struct.name}");
        }
</#if>
        int _numTaggedFields = 0;
<#list struct.fields as field>
<#local isTagged = field.taggedVersions?has_content && field.taggedVersions.lowest <= effHi>
<#if isTagged>
<#-- Count tagged field -->
<#local fromVer = field.taggedVersions.lowest>
<#local alwaysFlex = fromVer <= effLo>
<#if alwaysFlex>
<#-- Tagged in all valid flex versions - just check if non-default in flex guard -->
        if (_version >= ${flexLow}) {
            if (<@nonDefaultCheck field=field/>) {
                _numTaggedFields++;
            }
        }
<#else>
<#-- Tagged only from some version (not all valid versions) -->
        if (_version >= ${fromVer}) {
            if (<@nonDefaultCheck field=field/>) {
                _numTaggedFields++;
            }
        }<#if !field.ignorable> else {
            if (<@nonDefaultCheck field=field/>) {
                throw new UnsupportedVersionException("Attempted to write a non-default ${field.name?uncap_first} at version " + _version);
            }
        }</#if>
</#if>
<#else>
<#-- Write non-tagged field -->
<#local alwaysPresent = (field.versions.lowest <= effLo) && (field.versions.highest >= effHi)>
<#local fromVersion = !alwaysPresent && (field.versions.lowest gt effLo) && (field.versions.highest gte effHi)>
<#local throughVersion = !alwaysPresent && (field.versions.lowest <= effLo) && (field.versions.highest < effHi)>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#if alwaysPresent>
<#-- Write without version guard -->
<@writeFieldDirect field=field effFlex=effFlex indent="        " effLo=effLo/>
<#elseif fromVersion>
        if (_version >= ${field.versions.lowest}) {
<@writeFieldDirect field=field effFlex=effFlex indent="            " effLo=effLo/>
        }
<#if !field.ignorable>
 else {
            if (<@nonDefaultCheck field=field/>) {
                throw new UnsupportedVersionException("Attempted to write a non-default ${field.name?uncap_first} at version " + _version);
            }
        }
</#if>
<#elseif throughVersion>
        if (_version <= ${field.versions.highest}) {
<@writeFieldDirect field=field effFlex=effFlex indent="            " effLo=effLo/>
        } else {
<#if field.ignorable>
        }
<#else>
            if (<@nonDefaultCheck field=field/>) {
                throw new UnsupportedVersionException("Attempted to write a non-default ${field.name?uncap_first} at version " + _version);
            }
        }
</#if>
</#if>
</#if>
</#list>
        RawTaggedFieldWriter _rawWriter = RawTaggedFieldWriter.forFields(_unknownTaggedFields);
        _numTaggedFields += _rawWriter.numFields();
<#if inputSpec.flexibleVersions?has_content>
        if (_version >= ${flexLow}) {
            _writable.writeUnsignedVarint(_numTaggedFields);
<#list struct.fields as field>
<#if field.taggedVersions?has_content && field.taggedVersions.lowest <= effHi>
<#local fromVer = field.taggedVersions.lowest>
<#local needVersionGuard = (fromVer gt effLo)>
<#if needVersionGuard>
            if (_version >= ${fromVer}) {
</#if>
<#if field.type == 'string' && field.nullableVersions?has_content>
<#if needVersionGuard>
                if (${field.name?uncap_first} != null) {
                    _writable.writeUnsignedVarint(${field.tagInteger});
                    byte[] _stringBytes = _cache.getSerializedValue(this.${field.name?uncap_first});
                    _writable.writeUnsignedVarint(_stringBytes.length + ByteUtils.sizeOfUnsignedVarint(_stringBytes.length + 1));
                    _writable.writeUnsignedVarint(_stringBytes.length + 1);
                    _writable.writeByteArray(_stringBytes);
                }
<#else>
            if (${field.name?uncap_first} != null) {
                _writable.writeUnsignedVarint(${field.tagInteger});
                byte[] _stringBytes = _cache.getSerializedValue(this.${field.name?uncap_first});
                _writable.writeUnsignedVarint(_stringBytes.length + ByteUtils.sizeOfUnsignedVarint(_stringBytes.length + 1));
                _writable.writeUnsignedVarint(_stringBytes.length + 1);
                _writable.writeByteArray(_stringBytes);
            }
</#if>
<#elseif field.type.isStruct>
<#if needVersionGuard>
                {
                    if (!this.${field.name?uncap_first}.equals(new ${field.type}())) {
                        _writable.writeUnsignedVarint(${field.tagInteger});
                        _writable.writeUnsignedVarint(this.${field.name?uncap_first}.size(_cache, _version));
                        ${field.name?uncap_first}.write(_writable, _cache, _version);
                    }
                }
<#else>
            {
                if (!this.${field.name?uncap_first}.equals(new ${field.type}())) {
                    _writable.writeUnsignedVarint(${field.tagInteger});
                    _writable.writeUnsignedVarint(this.${field.name?uncap_first}.size(_cache, _version));
                    ${field.name?uncap_first}.write(_writable, _cache, _version);
                }
            }
</#if>
<#elseif field.type == 'uuid'>
<#local defVal = (field.defaultString == '')?then("Uuid.ZERO_UUID", "Uuid.fromString(\"" + field.defaultString + "\")")>
<#if needVersionGuard>
                {
                    if (!this.${field.name?uncap_first}.equals(${defVal})) {
                        _writable.writeUnsignedVarint(${field.tagInteger});
                        _writable.writeUnsignedVarint(16);
                        _writable.writeUuid(${field.name?uncap_first});
                    }
                }
<#else>
            {
                if (!this.${field.name?uncap_first}.equals(${defVal})) {
                    _writable.writeUnsignedVarint(${field.tagInteger});
                    _writable.writeUnsignedVarint(16);
                    _writable.writeUuid(${field.name?uncap_first});
                }
            }
</#if>
<#elseif field.type == 'int64'>
<#local defVal = (field.defaultString == '')?then("0L", field.defaultString + "L")>
<#if needVersionGuard>
                {
                    if (this.${field.name?uncap_first} != ${defVal}) {
                        _writable.writeUnsignedVarint(${field.tagInteger});
                        _writable.writeUnsignedVarint(8);
                        _writable.writeLong(${field.name?uncap_first});
                    }
                }
<#else>
            {
                if (this.${field.name?uncap_first} != ${defVal}) {
                    _writable.writeUnsignedVarint(${field.tagInteger});
                    _writable.writeUnsignedVarint(8);
                    _writable.writeLong(${field.name?uncap_first});
                }
            }
</#if>
</#if>
<#if needVersionGuard>
            }
</#if>
</#if>
</#list>
            _rawWriter.writeRawTags(_writable, Integer.MAX_VALUE);
        } else {
            if (_numTaggedFields > 0) {
                throw new UnsupportedVersionException("Tagged fields were set, but version " + _version + " of this message does not support them.");
            }
        }
<#else>
        if (_numTaggedFields > 0) {
            throw new UnsupportedVersionException("Tagged fields were set, but version " + _version + " of this message does not support them.");
        }
</#if>
    }
</#macro>

<#-- Write a single non-tagged field directly (used by generateWrite) -->
<#macro writeFieldDirect field effFlex indent effLo>
<#local effFl = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#local flexLow = effFl?has_content?then(effFl.lowest, 32767)>
<#if field.type == 'int32'>
${indent}_writable.writeInt(${field.name?uncap_first});
<#elseif field.type == 'int64'>
${indent}_writable.writeLong(${field.name?uncap_first});
<#elseif field.type == 'int8'>
${indent}_writable.writeByte(${field.name?uncap_first});
<#elseif field.type == 'int16'>
${indent}_writable.writeShort(${field.name?uncap_first});
<#elseif field.type == 'bool'>
${indent}_writable.writeByte(${field.name?uncap_first} ? (byte) 1 : (byte) 0);
<#elseif field.type == 'uuid'>
${indent}_writable.writeUuid(${field.name?uncap_first});
<#elseif field.type == 'string'>
${indent}{
${indent}    byte[] _stringBytes = _cache.getSerializedValue(${field.name?uncap_first});
${indent}    if (_version >= ${flexLow}) {
${indent}        _writable.writeUnsignedVarint(_stringBytes.length + 1);
${indent}    } else {
${indent}        _writable.writeShort((short) _stringBytes.length);
${indent}    }
${indent}    _writable.writeByteArray(_stringBytes);
${indent}}
<#elseif field.type.isStructArray>
<#local elemName = field.type.elementName>
${indent}if (_version >= ${flexLow}) {
${indent}    _writable.writeUnsignedVarint(${field.name?uncap_first}.size() + 1);
${indent}    for (${elemName} ${field.name?uncap_first}Element : ${field.name?uncap_first}) {
${indent}        ${field.name?uncap_first}Element.write(_writable, _cache, _version);
${indent}    }
${indent}} else {
${indent}    _writable.writeInt(${field.name?uncap_first}.size());
${indent}    for (${elemName} ${field.name?uncap_first}Element : ${field.name?uncap_first}) {
${indent}        ${field.name?uncap_first}Element.write(_writable, _cache, _version);
${indent}    }
${indent}}
<#elseif field.type.isArray>
<#local elemName = boxedElementType(field.type.elementType)>
${indent}if (_version >= ${flexLow}) {
${indent}    _writable.writeUnsignedVarint(${field.name?uncap_first}.size() + 1);
${indent}} else {
${indent}    _writable.writeInt(${field.name?uncap_first}.size());
${indent}}
${indent}for (${elemName} ${field.name?uncap_first}Element : ${field.name?uncap_first}) {
${indent}    _writable.writeInt(${field.name?uncap_first}Element);
${indent}}
</#if>
</#macro>

<#-- Generates the addSize() method body -->
<#macro generateAddSize struct effLo effHi dataClass isTopLevel>
<#local flexLow = inputSpec.flexibleVersions?has_content?then(inputSpec.flexibleVersions.lowest, 32767)>
    @Override
    public void addSize(MessageSizeAccumulator _size, ObjectSerializationCache _cache, short _version) {
        int _numTaggedFields = 0;
<#if !isTopLevel>
<#if effLo == inputSpec.validVersions.lowest>
        if ((_version < ${effLo}) || (_version > ${effHi})) {
            throw new UnsupportedVersionException("Can't size version " + _version + " of ${struct.name}");
        }
<#else>
        if (_version > ${effHi}) {
            throw new UnsupportedVersionException("Can't size version " + _version + " of ${struct.name}");
        }
</#if>
</#if>
<#list struct.fields as field>
<#local isTagged = field.taggedVersions?has_content && field.taggedVersions.lowest <= effHi>
<#if isTagged>
<#local fromVer = field.taggedVersions.lowest>
<#local needVersionGuard = (fromVer gt effLo)>
<#if needVersionGuard>
        if (_version >= ${fromVer}) {
<#else>
        if (_version >= ${flexLow}) {
</#if>
<#if field.type == 'string' && field.nullableVersions?has_content>
            if (${field.name?uncap_first} == null) {
            } else {
                _numTaggedFields++;
                _size.addBytes(1);
                byte[] _stringBytes = ${field.name?uncap_first}.getBytes(StandardCharsets.UTF_8);
                if (_stringBytes.length > 0x7fff) {
                    throw new RuntimeException("'${field.name?uncap_first}' field is too long to be serialized");
                }
                _cache.cacheSerializedValue(${field.name?uncap_first}, _stringBytes);
                int _stringPrefixSize = ByteUtils.sizeOfUnsignedVarint(_stringBytes.length + 1);
                _size.addBytes(_stringBytes.length + _stringPrefixSize + ByteUtils.sizeOfUnsignedVarint(_stringPrefixSize + _stringBytes.length));
            }
        }
<#elseif field.type.isStruct>
            {
                if (!this.${field.name?uncap_first}.equals(new ${field.type}())) {
                    _numTaggedFields++;
                    _size.addBytes(1);
                    int _sizeBeforeStruct = _size.totalSize();
                    this.${field.name?uncap_first}.addSize(_size, _cache, _version);
                    int _structSize = _size.totalSize() - _sizeBeforeStruct;
                    _size.addBytes(ByteUtils.sizeOfUnsignedVarint(_structSize));
                }
            }
        }
<#elseif field.type == 'uuid'>
<#local defVal = (field.defaultString == '')?then("Uuid.ZERO_UUID", "Uuid.fromString(\"" + field.defaultString + "\")")>
            if (!this.${field.name?uncap_first}.equals(${defVal})) {
                _numTaggedFields++;
                _size.addBytes(1);
                _size.addBytes(1);
                _size.addBytes(16);
            }
        }
<#elseif field.type == 'int64'>
<#local defVal = (field.defaultString == '')?then("0L", field.defaultString + "L")>
            if (this.${field.name?uncap_first} != ${defVal}) {
                _numTaggedFields++;
                _size.addBytes(1);
                _size.addBytes(1);
                _size.addBytes(8);
            }
        }
</#if>
<#else>
<#-- Non-tagged field size -->
<#local alwaysPresent = (field.versions.lowest <= effLo) && (field.versions.highest >= effHi)>
<#local fromVersion = !alwaysPresent && (field.versions.lowest gt effLo) && (field.versions.highest gte effHi)>
<#local throughVersion = !alwaysPresent && (field.versions.lowest <= effLo) && (field.versions.highest < effHi)>
<#local effFlex = field.flexibleVersions.orElse(inputSpec.flexibleVersions)>
<#local fxLow = effFlex?has_content?then(effFlex.lowest, 32767)>
<#if fromVersion>
        if (_version >= ${field.versions.lowest}) {
<@addSizeFieldDirect field=field fxLow=fxLow indent="            "/>
        }
<#elseif throughVersion>
        if (_version <= ${field.versions.highest}) {
<@addSizeFieldDirect field=field fxLow=fxLow indent="            "/>
        }
<#else>
<@addSizeFieldDirect field=field fxLow=fxLow indent="        "/>
</#if>
</#if>
</#list>
        if (_unknownTaggedFields != null) {
            _numTaggedFields += _unknownTaggedFields.size();
            for (RawTaggedField _field : _unknownTaggedFields) {
                _size.addBytes(ByteUtils.sizeOfUnsignedVarint(_field.tag()));
                _size.addBytes(ByteUtils.sizeOfUnsignedVarint(_field.size()));
                _size.addBytes(_field.size());
            }
        }
<#if inputSpec.flexibleVersions?has_content>
        if (_version >= ${flexLow}) {
            _size.addBytes(ByteUtils.sizeOfUnsignedVarint(_numTaggedFields));
        } else {
            if (_numTaggedFields > 0) {
                throw new UnsupportedVersionException("Tagged fields were set, but version " + _version + " of this message does not support them.");
            }
        }
<#else>
        if (_numTaggedFields > 0) {
            throw new UnsupportedVersionException("Tagged fields were set, but version " + _version + " of this message does not support them.");
        }
</#if>
    }
</#macro>

<#-- Add size for a single non-tagged field -->
<#macro addSizeFieldDirect field fxLow indent>
<#if field.type == 'int32'>
${indent}_size.addBytes(4);
<#elseif field.type == 'int64'>
${indent}_size.addBytes(8);
<#elseif field.type == 'int8'>
${indent}_size.addBytes(1);
<#elseif field.type == 'int16'>
${indent}_size.addBytes(2);
<#elseif field.type == 'bool'>
${indent}_size.addBytes(1);
<#elseif field.type == 'uuid'>
${indent}_size.addBytes(16);
<#elseif field.type == 'string'>
${indent}{
${indent}    byte[] _stringBytes = ${field.name?uncap_first}.getBytes(StandardCharsets.UTF_8);
${indent}    if (_stringBytes.length > 0x7fff) {
${indent}        throw new RuntimeException("'${field.name?uncap_first}' field is too long to be serialized");
${indent}    }
${indent}    _cache.cacheSerializedValue(${field.name?uncap_first}, _stringBytes);
${indent}    if (_version >= ${fxLow}) {
${indent}        _size.addBytes(_stringBytes.length + ByteUtils.sizeOfUnsignedVarint(_stringBytes.length + 1));
${indent}    } else {
${indent}        _size.addBytes(_stringBytes.length + 2);
${indent}    }
${indent}}
<#elseif field.type.isStructArray>
<#local elemName = field.type.elementName>
${indent}{
${indent}    if (_version >= ${fxLow}) {
${indent}        _size.addBytes(ByteUtils.sizeOfUnsignedVarint(${field.name?uncap_first}.size() + 1));
${indent}    } else {
${indent}        _size.addBytes(4);
${indent}    }
${indent}    for (${elemName} ${field.name?uncap_first}Element : ${field.name?uncap_first}) {
${indent}        ${field.name?uncap_first}Element.addSize(_size, _cache, _version);
${indent}    }
${indent}}
<#elseif field.type.isArray>
${indent}{
${indent}    if (_version >= ${fxLow}) {
${indent}        _size.addBytes(ByteUtils.sizeOfUnsignedVarint(${field.name?uncap_first}.size() + 1));
${indent}    } else {
${indent}        _size.addBytes(4);
${indent}    }
${indent}    _size.addBytes(${field.name?uncap_first}.size() * 4);
${indent}}
</#if>
</#macro>

<#-- Generates equals(), hashCode(), duplicate(), toString(), getters, setters -->
<#macro generateEquals struct dataClass>
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ${dataClass})) return false;
        ${dataClass} other = (${dataClass}) obj;
<#list struct.fields as field>
<#if field.type.canBeNullable>
        if (this.${field.name?uncap_first} == null) {
            if (other.${field.name?uncap_first} != null) return false;
        } else {
            if (!this.${field.name?uncap_first}.equals(other.${field.name?uncap_first})) return false;
        }
<#elseif field.type == 'uuid'>
        if (!this.${field.name?uncap_first}.equals(other.${field.name?uncap_first})) return false;
<#else>
        if (${field.name?uncap_first} != other.${field.name?uncap_first}) return false;
</#if>
</#list>
        return MessageUtil.compareRawTaggedFields(_unknownTaggedFields, other._unknownTaggedFields);
    }
</#macro>

<#macro generateHashCode struct>
    @Override
    public int hashCode() {
        int hashCode = 0;
<#list struct.fields as field>
<#if field.type.canBeNullable>
        hashCode = 31 * hashCode + (${field.name?uncap_first} == null ? 0 : ${field.name?uncap_first}.hashCode());
<#elseif field.type == 'uuid'>
        hashCode = 31 * hashCode + ${field.name?uncap_first}.hashCode();
<#elseif field.type == 'int64'>
        hashCode = 31 * hashCode + ((int) (${field.name?uncap_first} >> 32) ^ (int) ${field.name?uncap_first});
<#elseif field.type == 'bool'>
        hashCode = 31 * hashCode + (${field.name?uncap_first} ? 1231 : 1237);
<#else>
        hashCode = 31 * hashCode + ${field.name?uncap_first};
</#if>
</#list>
        return hashCode;
    }
</#macro>

<#macro generateDuplicate struct dataClass>
    @Override
    public ${dataClass} duplicate() {
        ${dataClass} _duplicate = new ${dataClass}();
<#list struct.fields as field>
<#if field.type == 'string' && field.nullableVersions?has_content>
        if (${field.name?uncap_first} == null) {
            _duplicate.${field.name?uncap_first} = null;
        } else {
            _duplicate.${field.name?uncap_first} = ${field.name?uncap_first};
        }
<#elseif field.type.isStruct>
        _duplicate.${field.name?uncap_first} = ${field.name?uncap_first}.duplicate();
<#elseif field.type.isStructArray>
<#local elemName = field.type.elementName>
        ArrayList<${elemName}> new${field.name} = new ArrayList<${elemName}>(${field.name?uncap_first}.size());
        for (${elemName} _element : ${field.name?uncap_first}) {
            new${field.name}.add(_element.duplicate());
        }
        _duplicate.${field.name?uncap_first} = new${field.name};
<#elseif field.type.isArray>
<#local elemName = boxedElementType(field.type.elementType)>
        ArrayList<${elemName}> new${field.name} = new ArrayList<${elemName}>(${field.name?uncap_first}.size());
        for (${elemName} _element : ${field.name?uncap_first}) {
            new${field.name}.add(_element);
        }
        _duplicate.${field.name?uncap_first} = new${field.name};
<#else>
        _duplicate.${field.name?uncap_first} = ${field.name?uncap_first};
</#if>
</#list>
        return _duplicate;
    }
</#macro>

<#macro generateToString struct dataClass>
    @Override
    public String toString() {
        return "${dataClass}("
<#list struct.fields as field>
<#if !field?is_first>            + ", ${field.name?uncap_first}=" + <#else>            + "${field.name?uncap_first}=" + </#if>
<#if field.type == 'string'>((${field.name?uncap_first} == null) ? "null" : "'" + ${field.name?uncap_first}.toString() + "'")
<#elseif field.type.isArray>MessageUtil.deepToString(${field.name?uncap_first}.iterator())
<#elseif field.type.canBeNullable && !field.type.isArray>((${field.name?uncap_first} == null) ? "null" : ${field.name?uncap_first}.toString())
<#else>${field.name?uncap_first}.toString()
</#if>
</#list>
            + ")";
    }
</#macro>

<#macro generateGettersSetters struct dataClass>
<#list struct.fields as field>

    public <@javaFieldType field=field/> ${field.name?uncap_first}() {
        return this.${field.name?uncap_first};
    }
</#list>

    @Override
    public List<RawTaggedField> unknownTaggedFields() {
        if (_unknownTaggedFields == null) {
            _unknownTaggedFields = new ArrayList<>(0);
        }
        return _unknownTaggedFields;
    }
<#list struct.fields as field>

    public ${dataClass} set${field.name}(<@javaFieldType field=field/> v) {
        this.${field.name?uncap_first} = v;
        return this;
    }
</#list>
</#macro>

<#-- Generates nested static inner classes -->
<#macro generateNestedClasses struct effLo effHi>
<#list struct.fields as field>
<#if field.type.isStructArray || field.type.isStruct>
<#local substruct = structRegistry.findStruct(field)>
<#local innerEffLo = effectiveLow(substruct)>
<#local innerEffHi = inputSpec.validVersions.highest>
<#local innerDataClass = field.type.isStructArray?then(field.type.elementName, field.type?string)>
<@generateInnerClass struct=substruct effLo=innerEffLo effHi=innerEffHi dataClass=innerDataClass/>
<#-- Recurse -->
<@generateNestedClasses struct=substruct effLo=innerEffLo effHi=innerEffHi/>
</#if>
</#list>
</#macro>

<#-- Generates a single static inner class -->
<#macro generateInnerClass struct effLo effHi dataClass>

    public static class ${dataClass} implements Message {
        <#list struct.fields as field>
        <@javaFieldType field=field/> ${field.name?uncap_first};
        </#list>
        private List<RawTaggedField> _unknownTaggedFields;

<@generateStructSchemas struct=struct effLo=effLo effHi=effHi/>

<@generateSchemasArray effLo=effLo effHi=effHi/>

        public static final short LOWEST_SUPPORTED_VERSION = ${effLo};
        public static final short HIGHEST_SUPPORTED_VERSION = ${effHi};

        public ${dataClass}(Readable _readable, short _version) {
            read(_readable, _version);
        }

        public ${dataClass}() {
<#list struct.fields as field>
            this.${field.name?uncap_first} = <@fieldDefault field=field/>;
</#list>
        }


        @Override
        public short lowestSupportedVersion() {
            return ${inputSpec.validVersions.lowest};
        }

        @Override
        public short highestSupportedVersion() {
            return ${inputSpec.validVersions.highest};
        }

<@generateRead struct=struct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=false/>

<@generateWrite struct=struct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=false/>

<@generateAddSize struct=struct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=false/>

<@generateEquals struct=struct dataClass=dataClass/>

<@generateHashCode struct=struct/>

<@generateDuplicate struct=struct dataClass=dataClass/>

<@generateToString struct=struct dataClass=dataClass/>
        <#list struct.fields as field>

        public <@javaFieldType field=field/> ${field.name?uncap_first}() {
            return this.${field.name?uncap_first};
        }
        </#list>

        @Override
        public List<RawTaggedField> unknownTaggedFields() {
            if (_unknownTaggedFields == null) {
                _unknownTaggedFields = new ArrayList<>(0);
            }
            return _unknownTaggedFields;
        }
        <#list struct.fields as field>

        public ${dataClass} set${field.name}(<@javaFieldType field=field/> v) {
            this.${field.name?uncap_first} = v;
            return this;
        }
        </#list>
    }
</#macro>

<#-- ============================================================
     Main output section starts here
     ============================================================ -->
<#assign dataClass = "${inputSpec.name}Data">
<#assign validVersions = inputSpec.validVersions>
<#assign effLo = validVersions.lowest>
<#assign effHi = validVersions.highest>
<#assign topStruct = inputSpec.struct>
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// THIS CODE IS AUTOMATICALLY GENERATED.  DO NOT EDIT.

package org.apache.kafka.common.message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Message;
import org.apache.kafka.common.protocol.MessageSizeAccumulator;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.protocol.Readable;
import org.apache.kafka.common.protocol.Writable;
import org.apache.kafka.common.protocol.types.ArrayOf;
import org.apache.kafka.common.protocol.types.CompactArrayOf;
import org.apache.kafka.common.protocol.types.Field;
import org.apache.kafka.common.protocol.types.RawTaggedField;
import org.apache.kafka.common.protocol.types.RawTaggedFieldWriter;
import org.apache.kafka.common.protocol.types.Schema;
import org.apache.kafka.common.protocol.types.Type;
import org.apache.kafka.common.utils.ByteUtils;

import static org.apache.kafka.common.protocol.types.Field.TaggedFieldsSection;


public class ${dataClass} implements ApiMessage {
<#list topStruct.fields as field>
    <@javaFieldType field=field/> ${field.name?uncap_first};
</#list>
    private List<RawTaggedField> _unknownTaggedFields;

<@generateStructSchemas struct=topStruct effLo=effLo effHi=effHi/>

<@generateSchemasArray effLo=effLo effHi=effHi/>

    public static final short LOWEST_SUPPORTED_VERSION = ${effLo};
    public static final short HIGHEST_SUPPORTED_VERSION = ${effHi};

    public ${dataClass}(Readable _readable, short _version) {
        read(_readable, _version);
    }

    public ${dataClass}() {
<#list topStruct.fields as field>
        this.${field.name?uncap_first} = <@fieldDefault field=field/>;
</#list>
    }

    @Override
    public short apiKey() {
        return ${inputSpec.apiKey.orElse(-1)};
    }

    @Override
    public short lowestSupportedVersion() {
        return ${effLo};
    }

    @Override
    public short highestSupportedVersion() {
        return ${effHi};
    }

<@generateRead struct=topStruct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=true/>

<@generateWrite struct=topStruct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=true/>

<@generateAddSize struct=topStruct effLo=effLo effHi=effHi dataClass=dataClass isTopLevel=true/>

<@generateEquals struct=topStruct dataClass=dataClass/>

<@generateHashCode struct=topStruct/>

<@generateDuplicate struct=topStruct dataClass=dataClass/>

<@generateToString struct=topStruct dataClass=dataClass/>

<@generateGettersSetters struct=topStruct dataClass=dataClass/>
    <@generateNestedClasses struct=topStruct effLo=effLo effHi=effHi/>
}
